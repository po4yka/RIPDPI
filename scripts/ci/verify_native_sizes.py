#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def verify(lib_dir: Path, baseline: dict) -> None:
    max_growth_percent = float(baseline["maxGrowthPercent"])
    libraries = baseline["libraries"]
    failures: list[str] = []

    for abi, abi_sizes in sorted(libraries.items()):
        abi_dir = lib_dir / abi
        if not abi_dir.is_dir():
            failures.append(f"Missing ABI directory {abi_dir}")
            continue

        for library, expected_size in sorted(abi_sizes.items()):
            library_path = abi_dir / library
            if not library_path.is_file():
                failures.append(f"Missing native library {library_path}")
                continue

            actual_size = library_path.stat().st_size
            allowed_size = math.ceil(expected_size * (1 + max_growth_percent / 100.0))
            if actual_size > allowed_size:
                failures.append(
                    f"{abi}/{library} size regression: baseline={expected_size} actual={actual_size} "
                    f"allowed={allowed_size} (+{max_growth_percent:.0f}%)",
                )

    if failures:
        raise ValueError("\n".join(failures))


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify packaged native library sizes against a checked-in baseline.")
    parser.add_argument(
        "--lib-dir",
        default="app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib",
        help="Directory containing ABI subdirectories with packaged .so files.",
    )
    parser.add_argument(
        "--baseline",
        default="scripts/ci/native-size-baseline.json",
        help="Checked-in JSON baseline file.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    lib_dir = (repo_root / args.lib_dir).resolve()
    baseline_path = (repo_root / args.baseline).resolve()

    if not lib_dir.is_dir():
        raise ValueError(f"Native library directory not found: {lib_dir}")
    if not baseline_path.is_file():
        raise ValueError(f"Native size baseline not found: {baseline_path}")

    verify(lib_dir, read_json(baseline_path))
    print(f"Verified native library sizes in {lib_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native size verification failed: {exc}", file=sys.stderr)
        raise
