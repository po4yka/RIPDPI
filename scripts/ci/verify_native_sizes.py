#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

TRACKED_LIBRARIES = ("libripdpi.so", "libripdpi-tunnel.so")


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def collect_sizes(lib_dir: Path, libraries: tuple[str, ...] = TRACKED_LIBRARIES) -> dict[str, dict[str, int]]:
    sizes: dict[str, dict[str, int]] = {}
    for abi_dir in sorted(path for path in lib_dir.iterdir() if path.is_dir()):
        abi_sizes: dict[str, int] = {}
        for library in libraries:
            library_path = abi_dir / library
            if library_path.is_file():
                abi_sizes[library] = library_path.stat().st_size
        if abi_sizes:
            sizes[abi_dir.name] = abi_sizes
    return sizes


def allowed_size(expected_size: int, max_growth_percent: float | None, max_growth_bytes: int | None) -> int:
    limits = []
    if max_growth_percent is not None:
        limits.append(math.ceil(expected_size * (1 + max_growth_percent / 100.0)))
    if max_growth_bytes is not None:
        limits.append(expected_size + max_growth_bytes)
    if not limits:
        return expected_size
    return min(limits)


def build_baseline_payload(
    sizes: dict[str, dict[str, int]],
    max_per_library_growth_bytes: int,
    max_total_growth_percent: float,
    max_total_growth_bytes: int,
) -> str:
    payload = {
        "maxPerLibraryGrowthBytes": max_per_library_growth_bytes,
        "maxTotalGrowthPercent": max_total_growth_percent,
        "maxTotalGrowthBytes": max_total_growth_bytes,
        "libraries": sizes,
    }
    return json.dumps(payload, indent=2) + "\n"


def verify(lib_dir: Path, baseline: dict) -> None:
    max_growth_percent = baseline.get("maxGrowthPercent")
    max_growth_percent = float(max_growth_percent) if max_growth_percent is not None else None
    max_growth_bytes = baseline.get("maxPerLibraryGrowthBytes")
    max_growth_bytes = int(max_growth_bytes) if max_growth_bytes is not None else None
    max_total_growth_percent = baseline.get("maxTotalGrowthPercent")
    max_total_growth_percent = float(max_total_growth_percent) if max_total_growth_percent is not None else None
    max_total_growth_bytes = baseline.get("maxTotalGrowthBytes")
    max_total_growth_bytes = int(max_total_growth_bytes) if max_total_growth_bytes is not None else None
    libraries = baseline["libraries"]
    failures: list[str] = []
    baseline_total = 0
    actual_total = 0

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
            allowed = allowed_size(expected_size, max_growth_percent, max_growth_bytes)
            if actual_size > allowed:
                failures.append(
                    f"{abi}/{library} size regression: baseline={expected_size} actual={actual_size} allowed={allowed}",
                )
            baseline_total += expected_size
            actual_total += actual_size

    total_allowed = allowed_size(baseline_total, max_total_growth_percent, max_total_growth_bytes)
    if actual_total > total_allowed:
        failures.append(
            f"Total native size regression: baseline={baseline_total} actual={actual_total} allowed={total_allowed}",
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
    parser.add_argument(
        "--dump-current",
        action="store_true",
        help="Print a baseline JSON payload using the current native library sizes.",
    )
    parser.add_argument(
        "--max-per-library-growth-bytes",
        type=int,
        default=131072,
        help="Allowed growth for any single tracked native library when dumping a baseline.",
    )
    parser.add_argument(
        "--max-total-growth-percent",
        type=float,
        default=2.0,
        help="Allowed total tracked native size growth percentage when dumping a baseline.",
    )
    parser.add_argument(
        "--max-total-growth-bytes",
        type=int,
        default=262144,
        help="Allowed total tracked native size growth bytes when dumping a baseline.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    lib_dir = (repo_root / args.lib_dir).resolve()
    baseline_path = (repo_root / args.baseline).resolve()

    if not lib_dir.is_dir():
        raise ValueError(f"Native library directory not found: {lib_dir}")
    if args.dump_current:
        print(
            build_baseline_payload(
                collect_sizes(lib_dir),
                args.max_per_library_growth_bytes,
                args.max_total_growth_percent,
                args.max_total_growth_bytes,
            ),
            end="",
        )
        return 0
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
