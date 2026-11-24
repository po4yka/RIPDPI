#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path


STARTUP_ITERATIONS = {"startupCold": 15, "startupWarm": 10}
STARTUP_METRICS = ("timeToInitialDisplayMs", "timeToFullDisplayMs")


def read_json(path: Path) -> dict:
    return json.loads(path.read_text())


def find_benchmark_files(results_dir: Path) -> list[Path]:
    """Glob for benchmark JSON files in the results directory."""
    patterns = ("**/*-benchmarkData.json", "**/benchmarkData.json")
    found: set[Path] = set()
    for pattern in patterns:
        found.update(results_dir.glob(pattern))
    return sorted(found)


def compute_p95(runs: list[float | int]) -> float:
    """Compute P95 from a runs array."""
    if not runs:
        return 0.0
    s = sorted(runs)
    return float(s[math.ceil(len(s) * 0.95) - 1])


def parse_benchmarks(results_dir: Path) -> dict[str, dict[str, dict[str, float]]]:
    """Parse benchmark JSON files and extract metrics.

    Returns a dict keyed by benchmark name, containing metric dicts
    with 'median' and 'p95' values.
    """
    files = find_benchmark_files(results_dir)
    if not files:
        return {}

    benchmarks: dict[str, dict[str, dict[str, float]]] = {}
    for path in files:
        data = read_json(path)
        for bench in data.get("benchmarks", []):
            if bench.get("className", "").rsplit(".", 1)[-1] != "StartupBenchmark":
                continue
            name = bench.get("name", "").split("[", 1)[0]
            if name not in STARTUP_ITERATIONS:
                continue
            if name in benchmarks:
                raise ValueError(f"Duplicate startup benchmark: {name}")

            results = bench.get("metrics", {})
            metrics: dict[str, dict[str, float]] = {}
            for metric_name in STARTUP_METRICS:
                metric = results.get(metric_name, {})
                median = metric.get("median")
                runs = metric.get("runs", [])
                if (
                    len(runs) != STARTUP_ITERATIONS[name]
                    or not valid_latency(median)
                    or not all(valid_latency(run) for run in runs)
                ):
                    raise ValueError(f"Missing or incomplete startup metric: {name}/{metric_name}")
                metrics[metric_name] = {"median": float(median), "p95": compute_p95(runs)}
            benchmarks[name] = metrics

    return benchmarks


def valid_latency(value: object) -> bool:
    return type(value) in (int, float) and math.isfinite(value) and value > 0


def validate_reference(baseline: dict) -> None:
    for case in STARTUP_ITERATIONS:
        for metric in STARTUP_METRICS:
            entry = baseline.get("benchmarks", {}).get(case, {}).get(metric, {})
            if not all(valid_latency(entry.get(statistic)) for statistic in ("median", "p95")):
                raise ValueError(f"Missing or invalid startup reference: {case}/{metric}")


def classify_threshold(name: str, cold_pct: float, warm_pct: float) -> float:
    """Return the regression threshold for a benchmark based on its name."""
    lower = name.lower()
    if "cold" in lower:
        return cold_pct
    if "warm" in lower:
        return warm_pct
    # Default to cold (more permissive) for unclassified benchmarks.
    return cold_pct


def check_regressions(
    current: dict[str, dict[str, dict[str, float]]],
    baseline: dict,
    cold_pct: float,
    warm_pct: float,
) -> tuple[list[str], list[dict]]:
    """Compare current benchmarks against baseline thresholds.

    Returns (failures, rows) where rows are dicts for markdown rendering.
    """
    baseline_benchmarks = baseline.get("benchmarks", {})
    failures: list[str] = []
    rows: list[dict] = []

    for bench_name, metrics in sorted(current.items()):
        threshold = classify_threshold(bench_name, cold_pct, warm_pct)
        baseline_metrics = baseline_benchmarks.get(bench_name, {})

        for metric_name, values in sorted(metrics.items()):
            cur_median = values["median"]
            cur_p95 = values["p95"]

            base_entry = baseline_metrics.get(metric_name)
            if base_entry is None:
                rows.append({
                    "benchmark": bench_name,
                    "metric": metric_name,
                    "baseline_median": "-",
                    "current_median": f"{cur_median:.1f}",
                    "baseline_p95": "-",
                    "current_p95": f"{cur_p95:.1f}",
                    "status": "NEW",
                })
                continue

            base_median = base_entry.get("median", 0.0)
            base_p95 = base_entry.get("p95", 0.0)

            median_regression = (
                ((cur_median - base_median) / base_median * 100.0) if base_median > 0 else 0.0
            )
            p95_regression = (
                ((cur_p95 - base_p95) / base_p95 * 100.0) if base_p95 > 0 else 0.0
            )

            failed = False
            if median_regression > threshold:
                failures.append(
                    f"{bench_name}/{metric_name} median regression: "
                    f"baseline={base_median:.1f} current={cur_median:.1f} "
                    f"delta=+{median_regression:.1f}% threshold={threshold:.0f}%"
                )
                failed = True
            if p95_regression > threshold:
                failures.append(
                    f"{bench_name}/{metric_name} P95 regression: "
                    f"baseline={base_p95:.1f} current={cur_p95:.1f} "
                    f"delta=+{p95_regression:.1f}% threshold={threshold:.0f}%"
                )
                failed = True

            status = "FAIL" if failed else "PASS"
            rows.append({
                "benchmark": bench_name,
                "metric": metric_name,
                "baseline_median": f"{base_median:.1f}",
                "current_median": f"{cur_median:.1f}",
                "baseline_p95": f"{base_p95:.1f}",
                "current_p95": f"{cur_p95:.1f}",
                "status": status,
            })

    return failures, rows


def build_baseline_payload(
    current: dict[str, dict[str, dict[str, float]]],
    cold_pct: float,
    warm_pct: float,
) -> str:
    """Build a baseline JSON payload from current benchmark results."""
    payload: dict = {
        "maxColdStartRegressionPercent": cold_pct,
        "maxWarmStartRegressionPercent": warm_pct,
        "benchmarks": current,
    }
    return json.dumps(payload, indent=2) + "\n"


def render_markdown(rows: list[dict], failures: list[str], warn_only: bool) -> str:
    """Render a markdown summary table."""
    lines: list[str] = []
    verdict = "PASS"
    if failures:
        verdict = "WARN (--warn-only)" if warn_only else "FAIL"
    lines.append(f"## Macrobenchmark Regression Check: {verdict}")
    lines.append("")

    if not rows:
        lines.append("No benchmark data found.")
        return "\n".join(lines) + "\n"

    lines.append(
        "| Benchmark | Metric | Baseline Median | Current Median "
        "| Baseline P95 | Current P95 | Status |"
    )
    lines.append("|---|---|---:|---:|---:|---:|---|")
    for row in rows:
        lines.append(
            f"| {row['benchmark']} | {row['metric']} "
            f"| {row['baseline_median']} | {row['current_median']} "
            f"| {row['baseline_p95']} | {row['current_p95']} "
            f"| {row['status']} |"
        )

    if failures:
        lines.append("")
        lines.append("### Regressions")
        lines.append("")
        for f in failures:
            lines.append(f"- {f}")

    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Check for performance regressions in Android macrobenchmark results.",
    )
    parser.add_argument(
        "--results-dir",
        default="baselineprofile/build/outputs/connected_android_test_additional_output",
        help="Directory containing benchmark JSON output files.",
    )
    parser.add_argument(
        "--baseline",
        default="scripts/ci/macrobenchmark-baseline.json",
        help="Git-tracked baseline JSON file.",
    )
    parser.add_argument(
        "--dump-current",
        action="store_true",
        help="Print a baseline JSON payload from current results and exit.",
    )
    parser.add_argument(
        "--warn-only",
        action="store_true",
        help="Exit 0 even when regressions are detected.",
    )
    parser.add_argument(
        "--markdown-output",
        default=None,
        help="Path to write a markdown summary file.",
    )
    parser.add_argument(
        "--max-cold-regression-percent",
        type=float,
        default=None,
        help="Override cold start regression threshold (default: from baseline).",
    )
    parser.add_argument(
        "--max-warm-regression-percent",
        type=float,
        default=None,
        help="Override warm start regression threshold (default: from baseline).",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    results_dir = (repo_root / args.results_dir).resolve()
    baseline_path = (repo_root / args.baseline).resolve()

    if not results_dir.is_dir():
        print(f"Results directory not found: {results_dir}", file=sys.stderr)
        return 1

    current = parse_benchmarks(results_dir)
    missing_cases = set(STARTUP_ITERATIONS) - current.keys()
    if missing_cases:
        print(f"Missing startup benchmarks: {', '.join(sorted(missing_cases))}", file=sys.stderr)
        return 1

    # Resolve thresholds: CLI overrides > baseline file > hardcoded defaults.
    cold_pct = 20.0
    warm_pct = 15.0

    if baseline_path.is_file():
        baseline = read_json(baseline_path)
        cold_pct = baseline.get("maxColdStartRegressionPercent", cold_pct)
        warm_pct = baseline.get("maxWarmStartRegressionPercent", warm_pct)
    else:
        baseline = {"benchmarks": {}}
        if not args.dump_current:
            print(f"Startup reference not found: {baseline_path}", file=sys.stderr)
            return 1

    if args.max_cold_regression_percent is not None:
        cold_pct = args.max_cold_regression_percent
    if args.max_warm_regression_percent is not None:
        warm_pct = args.max_warm_regression_percent

    if args.dump_current:
        print(build_baseline_payload(current, cold_pct, warm_pct), end="")
        return 0

    validate_reference(baseline)

    failures, rows = check_regressions(current, baseline, cold_pct, warm_pct)

    if args.markdown_output:
        md_path = Path(args.markdown_output)
        md_path.parent.mkdir(parents=True, exist_ok=True)
        md_path.write_text(render_markdown(rows, failures, args.warn_only))
        print(f"Markdown summary written to {md_path}")

    if failures:
        print("Macrobenchmark regressions detected:", file=sys.stderr)
        for f in failures:
            print(f"  {f}", file=sys.stderr)
        if args.warn_only:
            print("--warn-only: exiting with 0 despite regressions.", file=sys.stderr)
            return 0
        return 1

    print("Macrobenchmark regression check passed.")
    for row in rows:
        print(f"  {row['benchmark']}/{row['metric']}: {row['status']}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Macrobenchmark regression check failed: {exc}", file=sys.stderr)
        raise
