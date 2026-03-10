#!/usr/bin/env python3

from __future__ import annotations

import argparse
import re
import shutil
import subprocess
import sys
from pathlib import Path

EXPECTED_NEEDED = {
    "libripdpi.so": {"libc.so", "libdl.so", "liblog.so"},
    "libhev-socks5-tunnel.so": {"libc.so", "libm.so", "libdl.so", "liblog.so"},
}
REQUIRED_PAGE_ALIGNMENT = 16 * 1024


def read_gradle_property(repo_root: Path, name: str) -> str:
    for line in (repo_root / "gradle.properties").read_text().splitlines():
        if line.startswith(f"{name}="):
            return line.split("=", 1)[1].strip()
    raise ValueError(f"Missing Gradle property: {name}")


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
    if actual_abis != expected_abis:
        raise ValueError(f"ABI set mismatch. expected={sorted(expected_abis)} actual={sorted(actual_abis)}")

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
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    lib_dir = (repo_root / args.lib_dir).resolve()
    if not lib_dir.is_dir():
        raise ValueError(f"Native library directory not found: {lib_dir}")

    objdump_path = shutil.which("objdump")
    if not objdump_path:
        raise RuntimeError("objdump is required for ELF inspection")

    expected_abis = {
        abi.strip() for abi in read_gradle_property(repo_root, "ripdpi.nativeAbis").split(",") if abi.strip()
    }
    verify(lib_dir, expected_abis, objdump_path)
    print(f"Verified native ELF outputs in {lib_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"ELF verification failed: {exc}", file=sys.stderr)
        raise
