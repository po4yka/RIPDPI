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
import sys
import zipfile

from source_captures import PACKAGE, ROOT, VARIANT

INPUT_PATHS = (
    "app", "core", "native", "build-logic", "gradle", "quality", "testing",
    "config", "xray-protos", "baselineprofile", "scripts/native", "scripts/ci", "LICENSES",
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "lint.xml",
    ".github/actions/setup-android-rust", ".github/actions/build-xray",
    ".github/workflows/play-store-screenshots.yml", "play-store-screenshots/scripts/capture_build.py",
)
XRAY_NOTICES = "assets/notices/go-modules.json"
XRAY_FILES = ("libxray.aar", "libxray-artifact.json", "build-go.mod", "build-go.sum", XRAY_NOTICES)
XRAY_DIRECTORIES = {"assets", "assets/notices"}
XRAY_SOURCES = "libxray-sources.jar"
OUTPUT_METADATA = "output-metadata.json"
SEALED_APK = "app-play-debug.apk"
FILES = {SEALED_APK, OUTPUT_METADATA, *(f"xray/{name}" for name in XRAY_FILES)}
MANIFEST = "capture-build.json"
SCHEMA_VERSION = 3
CAPTURE_ABI = "x86_64"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def safe_path(root: Path, relative: str, *, allow_link_leaf: bool = False) -> Path:
    parts = PurePosixPath(relative)
    if not relative or parts.is_absolute() or ".." in parts.parts or str(parts) != relative or "\\" in relative:
        raise ValueError(f"unsafe relative path: {relative!r}")
    path = root
    components = parts.parts[:-1] if allow_link_leaf else parts.parts
    for component in components:
        path = path / component
        if path.is_symlink():
            raise ValueError(f"symlink not allowed: {relative}")
    return root.joinpath(*parts.parts)


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
    inventory = command(["git", "ls-files", "--cached", "--stage", "-z", "--", *INPUT_PATHS], root)
    modes = {}
    for entry in filter(None, inventory.split("\0")):
        metadata, name = entry.split("\t", 1)
        mode, _, stage = metadata.split()
        if stage != "0" or name in modes:
            raise ValueError(f"unmerged Android build input: {name}")
        modes[name] = mode

    result = {}
    for name, mode in sorted(modes.items()):
        path = safe_path(root, name, allow_link_leaf=mode == "120000")
        if mode == "120000":
            if not path.is_symlink():
                raise ValueError(f"missing tracked source symlink: {name}")
            target = os.readlink(path)
            target_name = os.path.normpath(os.path.join(os.path.dirname(name), target))
            if modes.get(target_name) not in {"100644", "100755"}:
                raise ValueError(f"source symlink must target a tracked regular build input: {name}")
            # Check the original path as well, so traversal through a directory
            # symlink cannot change what the normalized path refers to.
            ordinary(path.parent / target)
            result[name] = "symlink:" + hashlib.sha256(os.fsencode(target)).hexdigest()
        else:
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


def check_play_output(metadata_path: Path, filename: str, abi: str) -> None:
    ordinary(metadata_path)
    metadata = json.loads(metadata_path.read_text())
    if metadata.get("variantName") != VARIANT or metadata.get("applicationId") != PACKAGE:
        raise ValueError(f"Gradle output metadata must identify {VARIANT} for {PACKAGE}")
    matches = [
        output for output in metadata.get("elements", [])
        if output.get("outputFile") == filename
        and {"filterType": "ABI", "value": abi} in output.get("filters", [])
    ]
    if filename != f"app-play-{abi}-debug.apk" or len(matches) != 1:
        raise ValueError(f"Gradle output metadata must bind the {abi} Play APK")


def check_play_apk(apk: Path, abi: str) -> None:
    ordinary(apk)
    with zipfile.ZipFile(apk) as archive:
        library = f"lib/{abi}/libripdpi.so"
        if library not in archive.namelist() or archive.getinfo(library).file_size == 0:
            raise ValueError(f"APK has no {abi} native runtime")
    try:
        subprocess.run([sys.executable, str(ROOT / "scripts/ci/verify_play_privilege_boundary.py"),
                        "--edition", "play", str(apk)], check=True, timeout=120)
    except subprocess.CalledProcessError as error:
        raise ValueError("Capture APK failed the Play edition boundary check") from error


def check_payload(apk: Path, xray: Path, *, producer_directory: bool = False) -> None:
    check_play_apk(apk, CAPTURE_ABI)
    required = set(XRAY_FILES)
    # gomobile's buildSrcJar writes this companion beside the AAR, and
    # build-libxray.sh publishes the whole staging directory. It is not a
    # runtime input or covered by libxray-artifact.json, so never copy it into
    # the seal. The full producer verifier must already have passed in CI.
    allowed = required | ({XRAY_SOURCES} if producer_directory else set())
    companion = xray / XRAY_SOURCES
    if producer_directory and companion.exists():
        ordinary(companion)
    names = regular_tree_files(xray, XRAY_DIRECTORIES)
    if not required.issubset(names) or not names.issubset(allowed):
        raise ValueError("unexpected or missing Xray producer files: "
                         f"missing={sorted(required - names)}, unexpected={sorted(names - allowed)}")
    producer = json.loads((xray / "libxray-artifact.json").read_text())
    for name, key in (
        ("libxray.aar", "aarSha256"),
        ("build-go.mod", "goModSha256"),
        ("build-go.sum", "goSumSha256"),
        (XRAY_NOTICES, "noticesSha256"),
    ):
        if producer.get(key) != sha256(xray / name):
            raise ValueError(f"Xray producer hash mismatch: {name}")
    if producer.get("schemaVersion") != 2 or "x86_64" not in producer.get("abis", []):
        raise ValueError("missing Xray x86_64 producer provenance")
    with zipfile.ZipFile(xray / "libxray.aar") as archive:
        if "jni/x86_64/libgojni.so" not in archive.namelist():
            raise ValueError("Xray AAR has no x86_64 native library")


def regular_tree_files(root: Path, directories: set[str]) -> set[str]:
    ordinary(root, directory=True)
    found = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError("symlink in capture build bundle")
        relative = path.relative_to(root).as_posix()
        if path.is_dir():
            if relative not in directories:
                raise ValueError(f"unexpected bundle directory: {relative}")
        else:
            ordinary(path)
            found.add(relative)
    return found


def inventory(bundle: Path) -> dict[str, str]:
    found = regular_tree_files(bundle, {"xray", *(f"xray/{name}" for name in XRAY_DIRECTORIES)})
    if found != FILES | {MANIFEST}:
        raise ValueError("unexpected or missing capture build files")
    return {name: sha256(safe_path(bundle, name)) for name in sorted(FILES)}


def seal(bundle: Path, apk: Path, xray_artifacts: Path, root: Path = ROOT) -> dict:
    bundle, apk, xray_artifacts, root = map(Path, (bundle, apk, xray_artifacts, root))
    ordinary(bundle.parent, directory=True)
    output_metadata = apk.with_name(OUTPUT_METADATA)
    check_play_output(output_metadata, apk.name, CAPTURE_ABI)
    check_payload(apk, xray_artifacts, producer_directory=True)
    revision = command(["git", "rev-parse", "HEAD"], root)
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        raise ValueError("unknown original build revision")
    metadata = {"schemaVersion": SCHEMA_VERSION, "variant": VARIANT, "abi": CAPTURE_ABI,
                "producerApk": apk.name,
                "builtFromRevision": revision, "inputs": input_hashes(root), "toolchains": toolchains(root)}
    # Exclusive mkdir: never overwrite or delete an existing bundle, even on error.
    bundle.mkdir()
    (bundle / "xray").mkdir()
    shutil.copyfile(apk, bundle / SEALED_APK)
    shutil.copyfile(output_metadata, bundle / OUTPUT_METADATA)
    for name in XRAY_FILES:
        destination = bundle / "xray" / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(xray_artifacts / name, destination)
    metadata["files"] = {name: sha256(bundle / name) for name in sorted(FILES)}
    (bundle / MANIFEST).write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    return verify(bundle, root)


def verify(bundle: Path, root: Path = ROOT, expected_revision: str | None = None) -> dict:
    bundle, root = Path(bundle), Path(root)
    files = inventory(bundle)
    metadata = json.loads((bundle / MANIFEST).read_text())
    if (metadata.get("schemaVersion"), metadata.get("variant"), metadata.get("abi")) != (SCHEMA_VERSION, VARIANT, CAPTURE_ABI):
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
    check_play_output(bundle / OUTPUT_METADATA, metadata.get("producerApk"), CAPTURE_ABI)
    check_payload(bundle / SEALED_APK, bundle / "xray")
    return {"apk": bundle / SEALED_APK, "xray": bundle / "xray",
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
