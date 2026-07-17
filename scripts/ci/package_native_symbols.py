#!/usr/bin/env python3
"""Validate and package deterministic Android JNI debug-symbol sidecars."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

try:
    from scripts.ci.verify_native_elfs import EXPECTED_NATIVE_LIBRARIES
except ModuleNotFoundError:  # Direct execution sets sys.path to scripts/ci.
    from verify_native_elfs import EXPECTED_NATIVE_LIBRARIES


EXPECTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
MANIFEST_NAME = "manifest.json"
BUNDLE_NAME = "release-native-symbols.zip"
BUILD_ID_PATTERN = re.compile(r"Build ID:\s*([0-9a-fA-F]+)")
SECTION_PATTERN = re.compile(r"\[\s*\d+\]\s+(\S+)")


@dataclass(frozen=True)
class ElfMetadata:
    build_ids: tuple[str, ...]
    sections: frozenset[str]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def inspect_elf(path: Path, readelf: str) -> ElfMetadata:
    result = subprocess.run(
        [readelf, "--wide", "--sections", "--notes", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    output = result.stdout
    return ElfMetadata(
        build_ids=tuple(match.lower() for match in BUILD_ID_PATTERN.findall(output)),
        sections=frozenset(SECTION_PATTERN.findall(output)),
    )


def expected_symbol_paths(root: Path) -> set[Path]:
    return {
        root / abi / f"{library}.dbg"
        for abi in EXPECTED_ABIS
        for library in EXPECTED_NATIVE_LIBRARIES
    }


def expected_stripped_paths(root: Path) -> set[Path]:
    return {
        root / abi / library
        for abi in EXPECTED_ABIS
        for library in EXPECTED_NATIVE_LIBRARIES
    }


def validate_exact_tree(root: Path, expected: set[Path], label: str) -> None:
    if not root.is_dir():
        raise ValueError(f"{label} directory does not exist: {root}")

    actual_abis = {path.name for path in root.iterdir() if path.is_dir()}
    expected_abis = set(EXPECTED_ABIS)
    if actual_abis != expected_abis:
        raise ValueError(
            f"{label} ABI set mismatch: expected={sorted(expected_abis)} actual={sorted(actual_abis)}",
        )

    actual = {path for path in root.rglob("*") if path.is_file()}
    missing = sorted(str(path.relative_to(root)) for path in expected - actual)
    extra = sorted(str(path.relative_to(root)) for path in actual - expected)
    if missing:
        raise ValueError(f"{label} missing expected files: {missing}")
    if extra:
        raise ValueError(f"{label} contains unexpected files: {extra}")


def one_build_id(metadata: ElfMetadata, path: Path) -> str:
    if len(metadata.build_ids) != 1:
        raise ValueError(
            f"{path} must contain exactly one GNU build ID, found {len(metadata.build_ids)}",
        )
    return metadata.build_ids[0]


def deterministic_zip_entry(name: str) -> zipfile.ZipInfo:
    entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    # Store bytes verbatim. GitHub's artifact layer still compresses the bundle,
    # while the canonical inner ZIP stays identical across zlib versions.
    entry.compress_type = zipfile.ZIP_STORED
    entry.create_system = 3
    entry.external_attr = 0o100644 << 16
    return entry


def package_symbols(
    symbols_dir: Path,
    stripped_libs_dir: Path,
    output_dir: Path,
    readelf: str,
) -> dict[str, object]:
    symbols_dir = symbols_dir.resolve()
    stripped_libs_dir = stripped_libs_dir.resolve()
    output_dir = output_dir.resolve()
    validate_exact_tree(symbols_dir, expected_symbol_paths(symbols_dir), "symbol sidecar")
    validate_exact_tree(stripped_libs_dir, expected_stripped_paths(stripped_libs_dir), "stripped library")

    artifacts: list[dict[str, str]] = []
    seen_build_ids: dict[str, str] = {}
    for abi in EXPECTED_ABIS:
        for library in EXPECTED_NATIVE_LIBRARIES:
            debug_file = symbols_dir / abi / f"{library}.dbg"
            stripped_file = stripped_libs_dir / abi / library
            debug_metadata = inspect_elf(debug_file, readelf)
            stripped_metadata = inspect_elf(stripped_file, readelf)
            debug_build_id = one_build_id(debug_metadata, debug_file)
            stripped_build_id = one_build_id(stripped_metadata, stripped_file)
            if debug_build_id != stripped_build_id:
                raise ValueError(
                    f"build-ID mismatch for {abi}/{library}: debug={debug_build_id} stripped={stripped_build_id}",
                )
            existing = seen_build_ids.get(debug_build_id)
            artifact_key = f"{abi}/{library}"
            if existing is not None:
                raise ValueError(
                    f"duplicate GNU build ID {debug_build_id}: {existing} and {artifact_key}",
                )
            seen_build_ids[debug_build_id] = artifact_key

            if ".debug_line" not in debug_metadata.sections:
                raise ValueError(f"debug sidecar missing .debug_line: {debug_file}")
            leaked_debug_sections = sorted(
                section for section in stripped_metadata.sections if section.startswith(".debug")
            )
            if leaked_debug_sections:
                raise ValueError(
                    f"stripped library still contains debug sections {leaked_debug_sections}: {stripped_file}",
                )
            if ".symtab" in stripped_metadata.sections:
                raise ValueError(f"stripped library still contains .symtab: {stripped_file}")
            if ".gnu_debuglink" not in stripped_metadata.sections:
                raise ValueError(f"stripped library missing .gnu_debuglink: {stripped_file}")

            artifacts.append(
                {
                    "abi": abi,
                    "library": library,
                    "buildId": debug_build_id,
                    "debugPath": f"symbols/{abi}/{library}.dbg",
                    "debugSha256": sha256(debug_file),
                    "strippedSha256": sha256(stripped_file),
                },
            )

    manifest: dict[str, object] = {
        "schemaVersion": 1,
        "abis": list(EXPECTED_ABIS),
        "libraries": list(EXPECTED_NATIVE_LIBRARIES),
        "artifacts": artifacts,
    }
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    output_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = output_dir / MANIFEST_NAME
    bundle_path = output_dir / BUNDLE_NAME
    manifest_path.write_bytes(manifest_bytes)
    with zipfile.ZipFile(bundle_path, "w") as archive:
        archive.writestr(deterministic_zip_entry(MANIFEST_NAME), manifest_bytes)
        for abi in EXPECTED_ABIS:
            for library in EXPECTED_NATIVE_LIBRARIES:
                debug_file = symbols_dir / abi / f"{library}.dbg"
                archive.writestr(
                    deterministic_zip_entry(f"symbols/{abi}/{library}.dbg"),
                    debug_file.read_bytes(),
                )
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbols-dir", type=Path, required=True)
    parser.add_argument("--stripped-libs-dir", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--readelf", required=True, help="Pinned NDK llvm-readelf executable")
    args = parser.parse_args()
    package_symbols(args.symbols_dir, args.stripped_libs_dir, args.output_dir, args.readelf)
    print(f"Packaged deterministic native symbols in {args.output_dir.resolve()}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native symbol packaging failed: {exc}", file=sys.stderr)
        raise
