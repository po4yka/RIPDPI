"""Select a prepared Go binary without invoking Go's online toolchain switcher."""

import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

OUTBOUND_VERSION = "go1.27.0"


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def metadata_path(cache, version):
    if not re.fullmatch(r"go1\.\d+\.\d+", version):
        raise ValueError("invalid pinned Go version")
    return cache / f"{version}-toolchain.json"


def environment(root):
    return {
        "PATH": str(root / "bin") + os.pathsep + os.environ.get("PATH", ""),
        "GOROOT": str(root),
        "GOTOOLCHAIN": "local",
        "GOWORK": "off",
        "GOPROXY": "off",
        "GOSUMDB": "off",
    }


def prepare(cache, version, cwd):
    """Resolve/download and verify the exact toolchain while preparation is online."""
    path = metadata_path(cache, version)
    selected = json.loads(
        subprocess.check_output(
            ["go", "env", "-json", "GOVERSION", "GOROOT"],
            cwd=cwd,
            env=dict(os.environ, GOTOOLCHAIN=version, GOWORK="off"),
            text=True,
            timeout=120,
        )
    )
    if selected["GOVERSION"] != version:
        raise ValueError("prepared Go version differs from the oracle pin")
    root = Path(selected["GOROOT"]).resolve(strict=True)
    metadata = {
        "version": version,
        "root": str(root),
        "sha256": digest(root / "bin/go"),
    }
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", dir=cache, delete=False) as output:
        temporary = Path(output.name)
        json.dump(metadata, output)
        output.write("\n")
    temporary.replace(path)
    return metadata


def resolve(cache, version):
    """Verify the prepared binary and prevent runtime toolchain downloads."""
    metadata = json.loads(metadata_path(cache, version).read_text())
    root = Path(metadata["root"])
    if metadata["version"] != version or not root.is_absolute():
        raise ValueError("prepared Go identity does not match the oracle pin")
    executable = root / "bin/go"
    if digest(executable) != metadata["sha256"]:
        raise ValueError("prepared Go binary digest mismatch")
    env = environment(root)
    selected = json.loads(
        subprocess.check_output(
            [str(executable), "env", "-json", "GOVERSION", "GOROOT"],
            cwd=root,
            env=dict(os.environ, **env),
            text=True,
            timeout=15,
        )
    )
    if selected["GOVERSION"] != version or Path(selected["GOROOT"]).resolve() != root:
        raise ValueError("prepared Go executable reports a different identity")
    return env, {"version": version, "sha256": metadata["sha256"]}
