#!/usr/bin/env python3

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_NEEDED = {
    "libripdpi.so": {"libc.so", "libm.so", "libdl.so", "liblog.so"},
    "libripdpi-tunnel.so": {"libc.so", "libm.so", "libdl.so", "liblog.so"},
}
REQUIRED_PAGE_ALIGNMENT = 16 * 1024


def read_gradle_property(repo_root: Path, name: str) -> str:
    for line in (repo_root / "gradle.properties").read_text().splitlines():
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    raise ValueError(f"Missing Gradle property: {name}")


def read_optional_gradle_property(repo_root: Path, name: str) -> str | None:
    for line in (repo_root / "gradle.properties").read_text().splitlines():
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    return None


def parse_abi_list(value: str) -> list[str]:
    return [abi.strip() for abi in value.split(",") if abi.strip()]


def is_ci_build() -> bool:
    return bool(os.environ.get("CI"))


def is_release_like_lib_dir(lib_dir: Path) -> bool:
    release_like_markers = ("release", "bundle", "publish")
    return any(any(marker in part.lower() for marker in release_like_markers) for part in lib_dir.parts)


def resolved_expected_abis(repo_root: Path, lib_dir: Path, explicit_abis: str | None) -> set[str]:
    if explicit_abis:
        return set(parse_abi_list(explicit_abis))

    default_abis = set(parse_abi_list(read_gradle_property(repo_root, "ripdpi.nativeAbis")))
    local_override = read_optional_gradle_property(repo_root, "ripdpi.localNativeAbis")
    local_default = read_optional_gradle_property(repo_root, "ripdpi.localNativeAbisDefault")
    can_use_local_abis = not is_ci_build() and not is_release_like_lib_dir(lib_dir)

    if local_override and can_use_local_abis:
        return set(parse_abi_list(local_override))

    if local_default and can_use_local_abis:
        return set(parse_abi_list(local_default))

    return default_abis


def parse_alignment(raw: str) -> int:
    raw = raw.strip()
    if raw.startswith("2**"):
        return 1 << int(raw[3:])
    return int(raw, 16)


def inspect_elf(elf_path: Path, objdump_path: str) -> tuple[set[str], list[int]]:
    output = subprocess.run(
        [objdump_path, "-x", str(elf_path)],
        check=True,
        text=True,
        capture_output=True,
    ).stdout

    needed = set()
    alignments = []

    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith("NEEDED"):
            needed.add(stripped.split()[-1])

        if stripped.startswith("LOAD"):
            match = re.search(r"align\s+(0x[0-9a-fA-F]+|2\*\*\d+)", line)
            if match:
                alignments.append(parse_alignment(match.group(1)))

    if not alignments:
        raise ValueError(f"No LOAD segments found in {elf_path}")

    return needed, alignments


def verify(lib_dir: Path, expected_abis: set[str], objdump_path: str) -> None:
    actual_abis = {path.name for path in lib_dir.iterdir() if path.is_dir()}
    missing_abis = expected_abis - actual_abis
    if missing_abis:
        raise ValueError(f"ABI set mismatch. missing expected ABIs: {sorted(missing_abis)} actual={sorted(actual_abis)}")

    for abi in sorted(expected_abis):
        abi_dir = lib_dir / abi
        for lib_name, expected_needed in EXPECTED_NEEDED.items():
            elf_path = abi_dir / lib_name
            if not elf_path.is_file():
                raise ValueError(f"Missing {lib_name} for ABI {abi}: {elf_path}")

            needed, alignments = inspect_elf(elf_path, objdump_path)
            if needed != expected_needed:
                raise ValueError(
                    f"Unexpected NEEDED libs for {elf_path}. expected={sorted(expected_needed)} actual={sorted(needed)}",
                )

            bad_alignments = [value for value in alignments if value < REQUIRED_PAGE_ALIGNMENT]
            if bad_alignments:
                raise ValueError(
                    f"LOAD segment alignment below 16 KiB for {elf_path}: {bad_alignments}",
                )


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify ABI set and ELF metadata for packaged native libraries.")
    parser.add_argument(
        "--lib-dir",
        default="app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib",
        help="Directory containing ABI subdirectories with packaged .so files.",
    )
    parser.add_argument(
        "--abis",
        help=(
            "Comma-separated ABI list to verify. "
            "Defaults to local debug ABI policy for non-CI debug-like paths, otherwise ripdpi.nativeAbis."
        ),
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    lib_dir = (repo_root / args.lib_dir).resolve()
    if not lib_dir.is_dir():
        raise ValueError(f"Native library directory not found: {lib_dir}")

    objdump_path = shutil.which("objdump")
    if not objdump_path:
        raise RuntimeError("objdump is required for ELF inspection")

    expected_abis = resolved_expected_abis(repo_root, lib_dir, args.abis)
    verify(lib_dir, expected_abis, objdump_path)
    print(f"Verified native ELF outputs in {lib_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"ELF verification failed: {exc}", file=sys.stderr)
        raise
