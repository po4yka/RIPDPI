#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def discover_benchmarks(criterion_dir: Path) -> dict[str, dict[str, float]]:
    """Walk criterion_dir looking for */new/estimates.json files.

    Returns a dict mapping benchmark name to its current mean/median in nanoseconds.
    """
    results: dict[str, dict[str, float]] = {}
    if not criterion_dir.is_dir():
        return results
    for estimates_path in sorted(criterion_dir.rglob("new/estimates.json")):
        # The benchmark name is the directory that is the parent of "new/".
        bench_dir = estimates_path.parent.parent
        bench_name = str(bench_dir.relative_to(criterion_dir))
        try:
            data = read_json(estimates_path)
        except (json.JSONDecodeError, OSError) as exc:
            print(f"WARNING: skipping {estimates_path}: {exc}", file=sys.stderr)
            continue
        mean_ns = data.get("mean", {}).get("point_estimate")
        median_ns = data.get("median", {}).get("point_estimate")
        if mean_ns is None or median_ns is None:
            print(f"WARNING: skipping {bench_name}: missing mean or median estimate", file=sys.stderr)
            continue
        results[bench_name] = {"mean_ns": mean_ns, "median_ns": median_ns}
    return results


def filter_by_prefix(
    benchmarks: dict[str, dict[str, float]],
    prefixes: list[str],
) -> dict[str, dict[str, float]]:
    """Keep only benchmarks whose name starts with one of ``prefixes``.

    Used by the enforcing nightly lane to gate a specific bench family (e.g.
    ``protocol-throughput/``) without letting unrelated, noisier microbenches
    flap the gate. An empty ``prefixes`` list is a no-op (keep everything).
    """
    if not prefixes:
        return benchmarks
    return {name: values for name, values in benchmarks.items() if any(name.startswith(p) for p in prefixes)}


def build_baseline_payload(
    current: dict[str, dict[str, float]],
    max_regression_percent: float,
) -> str:
    payload: dict = {
        "maxRegressionPercent": max_regression_percent,
        "benchmarks": current,
    }
    return json.dumps(payload, indent=2) + "\n"


def compare(
    current: dict[str, dict[str, float]],
    baseline: dict,
    max_regression_override: float | None,
) -> tuple[list[str], list[str], list[str]]:
    """Compare current results against baseline.

    Returns (regressions, improvements, warnings).
    """
    threshold = max_regression_override if max_regression_override is not None else baseline["maxRegressionPercent"]
    baseline_benchmarks = baseline.get("benchmarks", {})
    regressions: list[str] = []
    improvements: list[str] = []
    warnings: list[str] = []

    for name, values in sorted(current.items()):
        if name not in baseline_benchmarks:
            warnings.append(f"New benchmark (no baseline): {name}")
            continue
        base = baseline_benchmarks[name]
        # Compare the median, not the mean: criterion's mean is dominated by the heavy tail
        # of slow samples on shared CI runners (e.g. ws_tunnel_1MiB's baseline mean is 2.4x
        # its median), which flaps this enforcing gate on noise rather than real regressions.
        # The median is the robust point estimate and is captured in every baseline entry.
        base_median = base.get("median_ns", base["mean_ns"])
        cur_median = values.get("median_ns", values["mean_ns"])
        if base_median == 0:
            warnings.append(f"Baseline median is zero for {name}, skipping comparison")
            continue
        change_pct = ((cur_median - base_median) / base_median) * 100.0
        direction = "slower" if change_pct > 0 else "faster"
        summary = (
            f"{name}: {base_median:.0f} -> {cur_median:.0f} ns "
            f"({change_pct:+.1f}%, {abs(change_pct):.1f}% {direction})"
        )
        if change_pct > threshold:
            regressions.append(summary)
        elif change_pct < -threshold:
            improvements.append(summary)

    for name in sorted(baseline_benchmarks):
        if name not in current:
            warnings.append(f"Missing benchmark (in baseline but not in results): {name}")

    return regressions, improvements, warnings


def format_markdown(
    regressions: list[str],
    improvements: list[str],
    warnings: list[str],
    current: dict[str, dict[str, float]],
    baseline: dict,
    threshold: float,
) -> str:
    lines: list[str] = []
    passed = len(regressions) == 0
    lines.append(f"## Criterion Benchmark Results {'(PASS)' if passed else '(REGRESSION DETECTED)'}")
    lines.append("")
    lines.append(f"Threshold: {threshold:.1f}%")
    lines.append("")

    if regressions:
        lines.append("### Regressions")
        lines.append("")
        for r in regressions:
            lines.append(f"- {r}")
        lines.append("")

    if improvements:
        lines.append("### Improvements")
        lines.append("")
        for i in improvements:
            lines.append(f"- {i}")
        lines.append("")

    if warnings:
        lines.append("### Warnings")
        lines.append("")
        for w in warnings:
            lines.append(f"- {w}")
        lines.append("")

    baseline_benchmarks = baseline.get("benchmarks", {})
    all_names = sorted(set(current) | set(baseline_benchmarks))
    if all_names:
        lines.append("### All Benchmarks")
        lines.append("")
        lines.append("| Benchmark | Baseline median (ns) | Current median (ns) | Change |")
        lines.append("|-----------|---------------------|--------------------|--------|")
        for name in all_names:
            base_median = baseline_benchmarks.get(name, {}).get("median_ns")
            cur_median = current.get(name, {}).get("median_ns")
            base_str = f"{base_median:.0f}" if base_median is not None else "n/a"
            cur_str = f"{cur_median:.0f}" if cur_median is not None else "n/a"
            if base_median is not None and cur_median is not None and base_median != 0:
                change_pct = ((cur_median - base_median) / base_median) * 100.0
                change_str = f"{change_pct:+.1f}%"
            else:
                change_str = "n/a"
            lines.append(f"| {name} | {base_str} | {cur_str} | {change_str} |")
        lines.append("")

    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check for performance regressions in criterion benchmark results.",
    )
    parser.add_argument(
        "--criterion-dir",
        default="native/rust/target/criterion",
        help="Path to criterion output directory.",
    )
    parser.add_argument(
        "--baseline",
        default="scripts/ci/rust-bench-baseline.json",
        help="Checked-in JSON baseline file.",
    )
    parser.add_argument(
        "--dump-current",
        action="store_true",
        help="Print a baseline JSON payload from current results and exit.",
    )
    parser.add_argument(
        "--warn-only",
        action="store_true",
        help="Exit 0 even on regressions, just print warnings.",
    )
    parser.add_argument(
        "--markdown-output",
        default=None,
        help="Optional path to write a markdown summary file.",
    )
    parser.add_argument(
        "--max-regression-percent",
        type=float,
        default=None,
        help="Override the baseline's maxRegressionPercent threshold.",
    )
    parser.add_argument(
        "--only-prefix",
        action="append",
        default=[],
        metavar="PREFIX",
        help=(
            "Restrict the comparison to benchmarks whose name starts with PREFIX "
            "(repeatable). Does not affect --dump-current, which always captures "
            "the full result set."
        ),
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    criterion_dir = (repo_root / args.criterion_dir).resolve()
    baseline_path = (repo_root / args.baseline).resolve()

    current = discover_benchmarks(criterion_dir)
    if not current:
        print(f"WARNING: no criterion results found in {criterion_dir}", file=sys.stderr)
        if args.dump_current:
            print("{}", end="")
            return 0
        print("No benchmarks to check -- passing.")
        return 0

    if args.dump_current:
        threshold = args.max_regression_percent if args.max_regression_percent is not None else 10.0
        print(build_baseline_payload(current, threshold), end="")
        return 0

    if not baseline_path.is_file():
        print(f"WARNING: baseline file not found at {baseline_path} -- first run, passing.", file=sys.stderr)
        return 0

    baseline = read_json(baseline_path)
    if args.only_prefix:
        current = filter_by_prefix(current, args.only_prefix)
        baseline["benchmarks"] = filter_by_prefix(baseline.get("benchmarks", {}), args.only_prefix)
    threshold = args.max_regression_percent if args.max_regression_percent is not None else baseline["maxRegressionPercent"]
    regressions, improvements, warnings = compare(current, baseline, args.max_regression_percent)

    # Print results to stdout.
    for w in warnings:
        print(f"WARNING: {w}")
    for i in improvements:
        print(f"IMPROVED: {i}")
    for r in regressions:
        print(f"REGRESSION: {r}")

    has_regressions = len(regressions) > 0

    if not has_regressions and not improvements and not warnings:
        print("All benchmarks within threshold.")

    # Write markdown summary if requested.
    if args.markdown_output is not None:
        md_path = Path(args.markdown_output)
        md_content = format_markdown(regressions, improvements, warnings, current, baseline, threshold)
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(md_content)
        print(f"Markdown summary written to {md_path}")

    if has_regressions and not args.warn_only:
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Criterion regression check failed: {exc}", file=sys.stderr)
        raise
