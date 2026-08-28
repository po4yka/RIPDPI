#!/usr/bin/env python3
"""Apply mandatory reviewed patches to fresh, checksum-verified upstream sources."""
from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from libxray_artifacts import PATCHES, policy


def run(arguments: list[str], directory: Path, capture: bool = False) -> str:
    result = subprocess.run(arguments, cwd=directory, check=True, text=True,
                            stdout=subprocess.PIPE if capture else None, timeout=600)
    return result.stdout or ""


def module_copy(source: Path, module: str, version: str, expected_sum: str, directory: Path) -> None:
    if not re.search(rf"{re.escape(module)}\s+{re.escape(version)}\s", (source / "go.mod").read_text()):
        raise ValueError(f"upstream dependency pin mismatch: {module}")
    downloaded = json.loads(run(["go", "mod", "download", "-json", f"{module}@{version}"], source, True))
    if downloaded.get("Sum") != expected_sum:
        raise ValueError(f"module checksum mismatch: {module}")
    run(["go", "mod", "verify"], source)
    directory.mkdir()
    prefix = f"{module}@{version}/"
    with zipfile.ZipFile(downloaded["Zip"]) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix):
                raise ValueError("unexpected module archive root")
            relative = Path(entry.filename[len(prefix):])
            if relative.is_absolute() or ".." in relative.parts:
                raise ValueError("unsafe module archive path")
            destination = directory / relative
            if entry.is_dir():
                destination.mkdir(parents=True, exist_ok=True)
            else:
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(entry))


def main() -> None:
    source = Path(sys.argv[1]).resolve()
    versions, _, patches = policy()
    if run(["git", "rev-parse", "HEAD"], source, True).strip() != patches["libxrayCommit"]:
        raise ValueError("libXray commit does not match the reviewed patch base")
    if run(["git", "status", "--porcelain"], source, True).strip():
        raise ValueError("upstream source must be a fresh clean checkout")
    modules = (
        ("github.com/xtls/xray-core", versions["xray-core"], patches["xrayCoreModuleSum"], "xray-core", "xray-core-protect-errors.patch"),
        ("google.golang.org/grpc", patches["grpc"], patches["grpcModuleSum"], "grpc", "grpc-stdlib-trailer-prefix.patch"),
    )
    for module, version, checksum, name, patch in modules:
        target = source.parent / name
        module_copy(source, module, "v" + version, checksum, target)
        run(["git", "apply", "--unidiff-zero", "--check", str(PATCHES / patch)], target)
        run(["git", "apply", "--unidiff-zero", str(PATCHES / patch)], target)
    patch = PATCHES / "libxray-managed-runtime.patch"
    run(["git", "apply", "--unidiff-zero", "--check", str(patch)], source)
    run(["git", "apply", "--unidiff-zero", str(patch)], source)
    for module, _, _, name, _ in modules:
        run(["go", "mod", "edit", "-replace", f"{module}=../{name}"], source)
    # gomobile's pinned source graph selects x/net 0.54.0. The existing gRPC
    # release needs only its obsolete TrailerPrefix reference moved to net/http.
    run(["go", "get", "golang.org/x/mobile@v" + versions["gomobile"]], source)
    run(["go", "mod", "verify"], source)
    actual = json.loads(run(["go", "list", "-m", "-json", "golang.org/x/mobile"], source, True))
    if actual.get("Version") != "v" + versions["gomobile"]:
        raise ValueError("gomobile source dependency drifted")


if __name__ == "__main__":
    main()
