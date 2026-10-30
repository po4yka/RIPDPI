#!/usr/bin/env python3
"""Check the per-ABI release APK size budget from one universal APK.

Ordinary CI builds release APKs without ABI splits. An ABI split APK holds the
universal APK's entries minus the native libraries of the other ABIs, so the
universal size minus those entries is an upper bound for each split APK. This
lets CI find a budget overrun before the release candidate build does.
"""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def estimate_abi_sizes(apk: Path) -> dict[str, int]:
    """Return the upper-bound split APK size in bytes for each ABI the APK holds."""
    lib_bytes: dict[str, int] = dict.fromkeys(ABIS, 0)
    with zipfile.ZipFile(apk) as archive:
        for entry in archive.infolist():
            parts = entry.filename.split("/")
            if len(parts) > 2 and parts[0] == "lib" and parts[1] in lib_bytes:
                lib_bytes[parts[1]] += entry.compress_size
    total = apk.stat().st_size
    present = [abi for abi in ABIS if lib_bytes[abi]]
    return {abi: total - sum(lib_bytes[other] for other in present if other != abi) for abi in present}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="universal release APK")
    parser.add_argument("--max-abi-apk-size-mib", type=float, required=True)
    args = parser.parse_args(argv)

    budget = int(args.max_abi_apk_size_mib * 1024 * 1024)
    sizes = estimate_abi_sizes(args.apk)
    if not sizes:
        print(f"{args.apk} holds no native libraries for a known ABI", file=sys.stderr)
        return 1
    failed = False
    for abi, size in sizes.items():
        status = "ok" if size <= budget else "FAIL"
        failed |= size > budget
        print(f"{abi}: {size / 1024 / 1024:.1f} MiB (budget {args.max_abi_apk_size_mib:.1f} MiB) {status}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
