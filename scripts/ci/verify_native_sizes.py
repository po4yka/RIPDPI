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


def build_size_report(lib_dir: Path, baseline: dict) -> dict:
    max_growth_percent = baseline.get("maxGrowthPercent")
    max_growth_percent = float(max_growth_percent) if max_growth_percent is not None else None
    max_growth_bytes = baseline.get("maxPerLibraryGrowthBytes")
    max_growth_bytes = int(max_growth_bytes) if max_growth_bytes is not None else None
    max_total_growth_percent = baseline.get("maxTotalGrowthPercent")
    max_total_growth_percent = float(max_total_growth_percent) if max_total_growth_percent is not None else None
    max_total_growth_bytes = baseline.get("maxTotalGrowthBytes")
    max_total_growth_bytes = int(max_total_growth_bytes) if max_total_growth_bytes is not None else None
    baseline_libraries = baseline["libraries"]
    current_libraries = collect_sizes(lib_dir, tuple(sorted({lib for abi in baseline_libraries.values() for lib in abi})))

    entries: list[dict] = []
    failures: list[str] = []
    baseline_total = 0
    current_total = 0

    for abi, abi_sizes in sorted(baseline_libraries.items()):
        abi_dir = lib_dir / abi
        if not abi_dir.is_dir():
            failures.append(f"Missing ABI directory {abi_dir}")
            entries.append(
                {
                    "abi": abi,
                    "library": None,
                    "status": "missing-abi",
                    "message": f"Missing ABI directory {abi_dir}",
                },
            )
            continue

        current_abi_sizes = current_libraries.get(abi, {})
        for library, expected_size in sorted(abi_sizes.items()):
            actual_size = current_abi_sizes.get(library)
            allowed = allowed_size(expected_size, max_growth_percent, max_growth_bytes)
            status = "ok"
            if actual_size is None:
                failures.append(f"Missing native library {abi_dir / library}")
                status = "missing"
            elif actual_size > allowed:
                failures.append(
                    f"{abi}/{library} size regression: baseline={expected_size} actual={actual_size} allowed={allowed}",
                )
                status = "regression"
                current_total += actual_size
            else:
                current_total += actual_size

            baseline_total += expected_size
            entries.append(
                {
                    "abi": abi,
                    "library": library,
                    "baselineSize": expected_size,
                    "currentSize": actual_size,
                    "allowedSize": allowed,
                    "deltaBytes": None if actual_size is None else actual_size - expected_size,
                    "status": status,
                },
            )

    total_allowed = allowed_size(baseline_total, max_total_growth_percent, max_total_growth_bytes)
    total_status = "ok"
    if current_total > total_allowed:
        failures.append(
            f"Total native size regression: baseline={baseline_total} actual={current_total} allowed={total_allowed}",
        )
        total_status = "regression"

    return {
        "trackedLibraries": list(TRACKED_LIBRARIES),
        "limits": {
            "maxGrowthPercent": max_growth_percent,
            "maxPerLibraryGrowthBytes": max_growth_bytes,
            "maxTotalGrowthPercent": max_total_growth_percent,
            "maxTotalGrowthBytes": max_total_growth_bytes,
        },
        "totals": {
            "baselineSize": baseline_total,
            "currentSize": current_total,
            "allowedSize": total_allowed,
            "deltaBytes": current_total - baseline_total,
            "status": total_status,
        },
        "libraries": entries,
        "failures": failures,
    }


def render_size_report_markdown(report: dict) -> str:
    lines = [
        "# Native Size Report",
        "",
        "| ABI | Library | Baseline | Current | Delta | Allowed | Status |",
        "| --- | --- | ---: | ---: | ---: | ---: | --- |",
    ]
    for entry in report["libraries"]:
        if entry.get("library") is None:
            lines.append(f"| {entry['abi']} | missing ABI | - | - | - | - | {entry['status']} |")
            continue
        current = "-" if entry["currentSize"] is None else str(entry["currentSize"])
        delta = "-" if entry["deltaBytes"] is None else f"{entry['deltaBytes']:+d}"
        lines.append(
            f"| {entry['abi']} | {entry['library']} | {entry['baselineSize']} | {current} | {delta} | {entry['allowedSize']} | {entry['status']} |",
        )

    totals = report["totals"]
    lines.extend(
        [
            "",
            "## Totals",
            "",
            f"- baseline: `{totals['baselineSize']}`",
            f"- current: `{totals['currentSize']}`",
            f"- delta: `{totals['deltaBytes']:+d}`",
            f"- allowed: `{totals['allowedSize']}`",
            f"- status: `{totals['status']}`",
        ],
    )
    if report["failures"]:
        lines.extend(["", "## Failures", ""])
        lines.extend(f"- {failure}" for failure in report["failures"])
    return "\n".join(lines) + "\n"


def verify(lib_dir: Path, baseline: dict) -> None:
    failures = build_size_report(lib_dir, baseline)["failures"]
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
    parser.add_argument("--report-json", help="Write a JSON size report to this path.")
    parser.add_argument("--report-md", help="Write a Markdown size report to this path.")
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

    baseline = read_json(baseline_path)
    report = build_size_report(lib_dir, baseline)
    if args.report_json:
        Path(args.report_json).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if args.report_md:
        Path(args.report_md).write_text(render_size_report_markdown(report), encoding="utf-8")
    if report["failures"]:
        raise ValueError("\n".join(report["failures"]))
    print(f"Verified native library sizes in {lib_dir}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native size verification failed: {exc}", file=sys.stderr)
        raise
