#!/usr/bin/env python3
"""Seal trusted CI capture outputs for content-checked reuse, not authentication.

Only accept bundles from the trusted build job. A hash manifest detects corruption
and input drift; it is not a signature or proof that arbitrary binaries are safe.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
INPUT_PATHS = (
    "app", "core", "native", "build-logic", "gradle", "quality", "testing",
    "config", "xray-protos", "baselineprofile", "scripts/native", "scripts/ci",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "lint.xml",
    ".github/actions/setup-android-rust", ".github/actions/build-xray",
    ".github/workflows/play-store-screenshots.yml", "play-store-screenshots/scripts/capture_build.py",
)
XRAY_FILES = ("libxray.aar", "libxray-artifact.json", "build-go.mod", "build-go.sum")
FILES = {"app.apk", *(f"xray/{name}" for name in XRAY_FILES)}
MANIFEST = "capture-build.json"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_path(root: Path, relative: str) -> Path:
    parts = PurePosixPath(relative)
    if not relative or parts.is_absolute() or ".." in parts.parts or str(parts) != relative or "\\" in relative:
        raise ValueError(f"unsafe relative path: {relative!r}")
    path = root
    for component in parts.parts:
        path = path / component
        if path.is_symlink():
            raise ValueError(f"symlink not allowed: {relative}")
    return path


def ordinary(path: Path, directory: bool = False) -> None:
    # Check ancestors too: a regular leaf through a symlink is not sufficient.
    if any(part.is_symlink() for part in (path, *path.parents)):
        raise ValueError(f"symlink not allowed: {path}")
    if not (path.is_dir() if directory else path.is_file()):
        raise ValueError(f"missing regular {'directory' if directory else 'file'}: {path}")


def command(args: list[str], root: Path) -> str:
    result = subprocess.run(args, cwd=root, check=True, capture_output=True, text=True, timeout=60)
    return result.stdout.strip()


def input_hashes(root: Path) -> dict[str, str]:
    paths = command(["git", "ls-files", "--cached", "-z", "--", *INPUT_PATHS], root).split("\0")
    result = {}
    for name in sorted(set(paths) - {""}):
        path = safe_path(root, name)
        ordinary(path)
        result[name] = sha256(path)
    if not result:
        raise ValueError("no tracked Android build inputs")
    return result


def android_packages(root: Path) -> dict[str, str]:
    """Installed package identities, not merely the requested Gradle versions."""
    sdk_home = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk_home:
        raise ValueError("unknown installed Android SDK")
    sdk = Path(sdk_home)
    properties = dict(line.split("=", 1) for line in (root / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    platform = properties["ripdpi.compileSdk"]
    minor = properties.get("ripdpi.compileSdkMinor", "0")
    if int(minor) > 0:
        platform += "." + minor
    packages = [f"platforms/android-{platform}", f"ndk/{properties['ripdpi.nativeNdkVersion']}",
                f"cmake/{properties['ripdpi.nativeCmakeVersion']}"]
    # AGP selects its default build-tools version. Record the complete installed
    # inventory conservatively rather than guessing which package is equivalent.
    tools = sdk / "build-tools"
    ordinary(tools, directory=True)
    build_tools = sorted(path for path in tools.iterdir() if path.is_dir())
    if not build_tools:
        raise ValueError("missing installed Android build-tools")
    packages += [f"build-tools/{path.name}" for path in build_tools]
    result = {}
    for name in packages:
        path = safe_path(sdk, name + "/source.properties")
        ordinary(path)
        result[name] = sha256(path)
    return result


def toolchains(root: Path) -> dict:
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    java21_home = os.environ.get("JAVA_HOME_21_X64")
    if not java21_home:
        raise ValueError("unknown installed Gradle JDK 21")
    result = {}
    for name, args, cwd in (
        ("java21", [str(Path(java21_home) / "bin/java"), "-version"], root),
        ("java", [java, "-version"], root),
        ("rustc", ["rustc", "--version", "--verbose"], root / "native/rust"),
        ("go", ["go", "version"], root),
    ):
        process = subprocess.run(args, cwd=cwd, check=True, capture_output=True, text=True, timeout=60)
        # java writes its version to stderr. Never record environment or paths.
        value = (process.stdout + process.stderr).strip()
        if not value or not re.search({"java21": r'version "21[."]', "java": r'version "', "rustc": r"^rustc ", "go": r"^go version go"}[name], value):
            raise ValueError(f"unknown {name} toolchain version")
        result[name] = value
    result["androidPackages"] = android_packages(root)
    return result


def check_payload(apk: Path, xray: Path) -> None:
    ordinary(apk)
    ordinary(xray, directory=True)
    if {path.name for path in xray.iterdir()} != set(XRAY_FILES):
        raise ValueError("unexpected or missing Xray producer files")
    for name in XRAY_FILES:
        ordinary(xray / name)
    producer = json.loads((xray / "libxray-artifact.json").read_text())
    for name, key in (("libxray.aar", "aarSha256"), ("build-go.mod", "goModSha256"), ("build-go.sum", "goSumSha256")):
        if producer.get(key) != sha256(xray / name):
            raise ValueError(f"Xray producer hash mismatch: {name}")
    if producer.get("schemaVersion") != 2 or "x86_64" not in producer.get("abis", []):
        raise ValueError("missing Xray x86_64 producer provenance")
    with zipfile.ZipFile(apk) as archive:
        if not any(re.fullmatch(r"lib/x86_64/[^/]+\.so", name) and archive.getinfo(name).file_size > 0 for name in archive.namelist()):
            raise ValueError("APK has no x86_64 native libraries")
    with zipfile.ZipFile(xray / "libxray.aar") as archive:
        if "jni/x86_64/libgojni.so" not in archive.namelist():
            raise ValueError("Xray AAR has no x86_64 native library")


def inventory(bundle: Path) -> dict[str, str]:
    ordinary(bundle, directory=True)
    found = set()
    for path in bundle.rglob("*"):
        if path.is_symlink():
            raise ValueError("symlink in capture build bundle")
        relative = path.relative_to(bundle).as_posix()
        if path.is_dir():
            if relative != "xray":
                raise ValueError(f"unexpected bundle directory: {relative}")
        else:
            ordinary(path)
            found.add(relative)
    if found != FILES | {MANIFEST}:
        raise ValueError("unexpected or missing capture build files")
    return {name: sha256(safe_path(bundle, name)) for name in sorted(FILES)}


def seal(bundle: Path, apk: Path, xray_artifacts: Path, root: Path = ROOT) -> dict:
    bundle, apk, xray_artifacts, root = map(Path, (bundle, apk, xray_artifacts, root))
    ordinary(bundle.parent, directory=True)
    check_payload(apk, xray_artifacts)
    revision = command(["git", "rev-parse", "HEAD"], root)
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("unknown original build revision")
    metadata = {"schemaVersion": 1, "variant": "githubFullDebug", "abi": "x86_64",
                "builtFromRevision": revision, "inputs": input_hashes(root), "toolchains": toolchains(root)}
    # Exclusive mkdir: never overwrite or delete an existing bundle, even on error.
    bundle.mkdir()
    (bundle / "xray").mkdir()
    shutil.copyfile(apk, bundle / "app.apk")
    for name in XRAY_FILES:
        shutil.copyfile(xray_artifacts / name, bundle / "xray" / name)
    metadata["files"] = {name: sha256(bundle / name) for name in sorted(FILES)}
    (bundle / MANIFEST).write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    return verify(bundle, root)


def verify(bundle: Path, root: Path = ROOT, expected_revision: str | None = None) -> dict:
    bundle, root = Path(bundle), Path(root)
    files = inventory(bundle)
    metadata = json.loads((bundle / MANIFEST).read_text())
    if (metadata.get("schemaVersion"), metadata.get("variant"), metadata.get("abi")) != (1, "githubFullDebug", "x86_64"):
        raise ValueError("unsupported capture build metadata")
    if not re.fullmatch(r"[0-9a-f]{40}", metadata.get("builtFromRevision", "")):
        raise ValueError("missing original build revision")
    if expected_revision is not None and metadata["builtFromRevision"] != expected_revision:
        raise ValueError("original build revision does not match trusted producer run")
    if metadata.get("files") != files:
        raise ValueError("capture build content hash mismatch")
    if metadata.get("inputs") != input_hashes(root):
        raise ValueError("Android build inputs changed; rebuild required")
    if metadata.get("toolchains") != toolchains(root):
        raise ValueError("build toolchains changed; rebuild required")
    check_payload(bundle / "app.apk", bundle / "xray")
    return {"apk": bundle / "app.apk", "xray": bundle / "xray",
            "builtFromRevision": metadata["builtFromRevision"], "metadata": metadata}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("seal", "verify"):
        child = sub.add_parser(action)
        child.add_argument("bundle", type=Path)
        if action == "verify":
            child.add_argument("--expected-revision", help="Original trusted producer workflow head SHA")
        if action == "seal":
            child.add_argument("--apk", required=True, type=Path)
            child.add_argument("--xray-artifacts", required=True, type=Path)
    args = parser.parse_args()
    result = seal(args.bundle, args.apk, args.xray_artifacts) if args.action == "seal" else verify(args.bundle, expected_revision=args.expected_revision)
    print(json.dumps(result, default=str, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        raise SystemExit(f"FAIL: {error}") from error
