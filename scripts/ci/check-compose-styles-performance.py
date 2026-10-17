#!/usr/bin/env python3
"""Compare two complete series for original, dependencies, and styles APKs.

Manifest: {"runs": [{"state": "original", "series": 1,
"result_files": ["run-1-benchmarkData.json"], "device_serial": "SERIAL",
"source_sha": "<40 hex characters>", "apk_path": "original.apk",
"apk_sha256": "<64 hex characters>"}, ...]}. Supply all six state/series pairs.
Paths are relative to the manifest. Run the same ten-iteration benchmark harness
on the same device, OS, and recorded execution conditions for every state.
Each state needs a distinct source SHA and APK digest. Every run needs independent
JSON content; copying or reformatting another run's result is rejected.
Unknown context values reported by Benchmark 1.5.0 are retained with warnings:
cpuMaxFreqHz=-1, memTotalBytes=-1, cpuCoreCount=0. They must match across all runs.
context.compilationMode is the instrumentation target APK's compiler filter;
it does not prove CompilationMode.Full for the measured application. Keep harness
source and successful instrumentation logs for that separate check.

Published sources for BenchmarkData, ResultWriter, CpuInfo, MemInfo, PackageInfo,
and MetricResult:
https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-common/1.5.0/benchmark-common-1.5.0-sources.jar
Macrobenchmark and MacrobenchmarkRule:
https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-macro/1.5.0/benchmark-macro-1.5.0-sources.jar
https://dl.google.com/dl/android/maven2/androidx/benchmark/benchmark-macro-junit4/1.5.0/benchmark-macro-junit4-1.5.0-sources.jar

Exit codes: 0 PASS, 1 repeated regression, 2 invalid evidence, 3 INCONCLUSIVE.
Synthetic unit tests validate this parser; they do not establish app performance.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys


STATES = ("original", "dependencies", "styles")
SCENARIOS = ("startupCold", "settingsScroll", "logsScroll", "controlInteractions")
BENCHMARK_CLASS = "com.poyka.ripdpi.performance.ComposeStylesBenchmark"
ITERATIONS = 10
THRESHOLD_PERCENT = 5


def object_value(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label}: expected an object")
    return value


def text_value(value, label):
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label}: expected a nonempty string")
    return value


def positive(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{label}: expected a finite, positive number")
    try:
        number = float(value)
    except OverflowError as exc:
        raise ValueError(f"{label}: number is too large") from exc
    if not math.isfinite(number) or number <= 0:
        raise ValueError(f"{label}: expected a finite, positive number")
    return number


def read_json(path):
    try:
        return object_value(json.loads(path.read_text()), str(path))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Cannot read JSON {path}: {exc}") from exc


def percentile(values, fraction):
    """Published 1.5.0 MetricResult.getPercentile interpolates at (N - 1) * p."""
    ordered = sorted(values)
    index = (len(ordered) - 1) * fraction
    lower = int(index)
    upper = min(lower + 1, len(ordered) - 1)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (index - lower)


def context_identity(data, label):
    context = object_value(data.get("context"), f"{label}/context")
    build = object_value(context.get("build"), f"{label}/context/build")
    version = object_value(build.get("version"), f"{label}/context/build/version")
    identity = {key: text_value(build.get(key), f"{label}/build/{key}")
                for key in ("brand", "device", "fingerprint", "id", "model", "type")}
    sdk = version.get("sdk")
    if type(sdk) is not int or sdk < 29:
        raise ValueError(f"{label}: benchmark context must identify API 29 or newer")
    identity["sdk"] = sdk
    identity["codename"] = text_value(version.get("codename"), f"{label}/version/codename")
    # Published 1.5.0 CpuInfo.kt falls back to empty coreDirs and maxFreqHz=-1;
    # MemInfo.kt reports -1 when MemTotal is absent. Zero frequency is not a sentinel.
    for key in ("cpuCoreCount", "cpuMaxFreqHz", "memTotalBytes"):
        value = context.get(key)
        if type(value) is not int:
            raise ValueError(f"{label}/context/{key}: expected an integer")
        sentinel = 0 if key == "cpuCoreCount" else -1
        if value <= 0 and value != sentinel:
            raise ValueError(f"{label}/context/{key}: expected a positive integer or unknown sentinel {sentinel}")
        identity[key] = value
    for key in ("cpuLocked", "sustainedPerformanceModeEnabled"):
        if type(context.get(key)) is not bool:
            raise ValueError(f"{label}/context/{key}: expected a boolean")
        identity[key] = context[key]
    # PackageInfo.kt reads the instrumentation target package, not the app measured by Macrobenchmark.
    identity["compilationMode"] = text_value(context.get("compilationMode"), f"{label}/context/compilationMode")
    # ART updates affect the comparison when the producing library records them.
    art_version = context.get("artMainlineVersion")
    if art_version is not None and (type(art_version) is not int or art_version < -1):
        raise ValueError(f"{label}/artMainlineVersion: expected an integer, or -1 when unknown")
    os_codename = context.get("osCodenameAbbreviated")
    if os_codename is not None:
        text_value(os_codename, f"{label}/osCodenameAbbreviated")
    identity.update(artMainlineVersion=art_version, osCodenameAbbreviated=os_codename)
    return identity


def benchmark_metric(benchmark):
    name = benchmark["name"]
    if benchmark.get("repeatIterations") != ITERATIONS or type(benchmark.get("repeatIterations")) is not int:
        raise ValueError(f"{name}: expected exactly {ITERATIONS} iterations")
    startup = name == "startupCold"
    section = "metrics" if startup else "sampledMetrics"
    metric_name = "timeToInitialDisplayMs" if startup else "frameDurationCpuMs"
    metrics = object_value(benchmark.get(section), f"{name}/{section}")
    metric = object_value(metrics.get(metric_name), f"{name}/{metric_name}")
    runs = metric.get("runs")
    if not isinstance(runs, list) or len(runs) != ITERATIONS:
        raise ValueError(f"{name}/{metric_name}: expected {ITERATIONS} run entries")
    samples = []
    for run in runs:
        if startup:
            samples.append(positive(run, f"{name}/{metric_name}/runs"))
        else:
            if not isinstance(run, list) or not run:
                raise ValueError(f"{name}/{metric_name}: every iteration needs frame samples")
            samples.extend(positive(value, f"{name}/{metric_name}/runs") for value in run)
    statistic = "median" if startup else "p95"
    result = statistics.median(samples) if startup else percentile(samples, 0.95)
    reported = positive(metric.get("median" if startup else "P95"), f"{name}/{metric_name}/{statistic}")
    if not math.isclose(result, reported, rel_tol=1e-6, abs_tol=1e-6):
        raise ValueError(f"{name}/{metric_name}: reported {statistic} does not match raw runs")
    return f"{name}/{metric_name}:{statistic}", result


def file_path(root, value, label):
    path = (root / text_value(value, label)).resolve()
    if not path.is_file():
        raise ValueError(f"{label}: file does not exist: {path}")
    return path


def hex_value(value, length, label):
    if not isinstance(value, str) or not re.fullmatch(rf"[0-9a-f]{{{length}}}", value):
        raise ValueError(f"{label}: expected {length} lowercase hex characters")
    return value


def apk_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_run(entry, root, seen_files, seen_contents, hashed_apks):
    state, series = entry.get("state"), entry.get("series")
    if state not in STATES or type(series) is not int or series not in (1, 2):
        raise ValueError("Each run needs state original/dependencies/styles and series 1/2")
    source = hex_value(entry.get("source_sha"), 40, "source_sha")
    expected_hash = hex_value(entry.get("apk_sha256"), 64, "apk_sha256")
    apk = file_path(root, entry.get("apk_path"), "apk_path")
    if apk not in hashed_apks:
        hashed_apks[apk] = apk_digest(apk)
    if hashed_apks[apk] != expected_hash:
        raise ValueError(f"APK hash mismatch: {apk}")
    serial = text_value(entry.get("device_serial"), "device_serial")
    files = entry.get("result_files")
    if not isinstance(files, list) or not files:
        raise ValueError("Every run needs explicit result_files")
    contexts, metrics, methods, paths = [], {}, set(), []
    for value in files:
        path = file_path(root, value, "result_files")
        if path in seen_files:
            raise ValueError(f"Result file is reused: {path}")
        seen_files.add(path)
        paths.append(str(path))
        data = read_json(path)
        canonical = json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
        content_hash = hashlib.sha256(canonical.encode()).hexdigest()
        if content_hash in seen_contents:
            raise ValueError(f"Result content is reused: {path}; first seen at {seen_contents[content_hash]}")
        seen_contents[content_hash] = path
        contexts.append(context_identity(data, str(path)))
        benchmarks = data.get("benchmarks")
        if not isinstance(benchmarks, list) or not benchmarks:
            raise ValueError(f"{path}: missing benchmarks")
        for benchmark in benchmarks:
            benchmark = object_value(benchmark, f"{path}/benchmark")
            name = benchmark.get("name")
            if benchmark.get("className") != BENCHMARK_CLASS or name not in SCENARIOS:
                continue
            if name in methods:
                raise ValueError(f"{state}/{series}: duplicate benchmark {name}")
            methods.add(name)
            key, measurement = benchmark_metric(benchmark)
            metrics[key] = measurement
    if methods != set(SCENARIOS):
        raise ValueError(f"{state}/{series}: missing required scenarios: {sorted(set(SCENARIOS) - methods)}")
    if any(context != contexts[0] for context in contexts):
        raise ValueError(f"{state}/{series}: result files have different device contexts")
    return {"state": state, "series": series, "source_sha": source, "apk_sha256": expected_hash,
            "apk_path": str(apk), "device_serial": serial, "result_files": paths,
            "context": contexts[0], "metrics": metrics}


def evaluate(manifest_path):
    manifest_path = Path(manifest_path).resolve()
    entries = read_json(manifest_path).get("runs")
    if not isinstance(entries, list) or len(entries) != 6:
        raise ValueError("Manifest must contain exactly six runs: three states, two series")
    runs, seen_files, seen_contents, hashed_apks = {}, set(), {}, {}
    for entry in entries:
        run = read_run(object_value(entry, "run"), manifest_path.parent, seen_files, seen_contents, hashed_apks)
        key = run["state"], run["series"]
        if key in runs:
            raise ValueError(f"Duplicate state/series: {key}")
        runs[key] = run
    reference = next(iter(runs.values()))
    if any((run["device_serial"], run["context"]) != (reference["device_serial"], reference["context"])
           for run in runs.values()):
        raise ValueError("All runs must use the same device serial, OS, and execution context")
    for state in STATES:
        first, second = runs[state, 1], runs[state, 2]
        if (first["source_sha"], first["apk_sha256"]) != (second["source_sha"], second["apk_sha256"]):
            raise ValueError(f"{state}: both series must use the same source SHA and APK")
    for field in ("source_sha", "apk_sha256"):
        if len({runs[state, 1][field] for state in STATES}) != len(STATES):
            raise ValueError(f"Each of the three states must have a distinct {field}")
    comparisons = []
    for before, after in (("original", "dependencies"), ("dependencies", "styles"), ("original", "styles")):
        for series in (1, 2):
            for metric, baseline in runs[before, series]["metrics"].items():
                current = runs[after, series]["metrics"][metric]
                boundary = baseline * (1 + THRESHOLD_PERCENT / 100)
                exceeded = current > boundary and not math.isclose(current, boundary, rel_tol=1e-12)
                change = (current / baseline - 1) * 100
                if not math.isfinite(change):
                    raise ValueError(f"{metric}: values produce a nonfinite comparison")
                comparisons.append({"comparison": f"{before}->{after}", "series": series, "metric": metric,
                                    "before_ms": baseline, "after_ms": current,
                                    "change_percent": change, "over_threshold": exceeded})
    reproducible, uncertain = [], []
    for metric in reference["metrics"]:
        count = sum(row["over_threshold"] for row in comparisons
                    if row["comparison"] == "original->styles" and row["metric"] == metric)
        if count == 2:
            reproducible.append(metric)
        elif count == 1:
            uncertain.append(metric)
    status = "FAIL" if reproducible else "INCONCLUSIVE" if uncertain else "PASS"
    unknown_metadata = [
        f"context.{key}={sentinel}: {description} is unknown."
        for key, sentinel, description in (
            ("cpuMaxFreqHz", -1, "maximum CPU frequency"),
            ("memTotalBytes", -1, "total memory"),
            ("cpuCoreCount", 0, "CPU core count"),
        ) if reference["context"][key] == sentinel
    ]
    return {"status": status, "threshold_percent": THRESHOLD_PERCENT, "comparisons": comparisons,
            "reproducible_regressions": reproducible, "single_series_regressions": uncertain,
            "unknown_metadata_warnings": unknown_metadata,
            "runs": [runs[state, series] for state in STATES for series in (1, 2)],
            "provenance_note": "Source SHAs and device serials are manifest declarations; APK bytes and JSON contexts are checked. "
                               "Run logs are still required to connect those declarations to execution."}


def render_markdown(report):
    lines = [f"## Compose Styles performance: {report['status']}", "",
             "Final acceptance compares styles with original at 5% in both series for the same metric.", "",
             "| Comparison | Series | Scenario / metric | Before ms | After ms | Change | >5% |",
             "|---|---:|---|---:|---:|---:|---|"]
    for row in report["comparisons"]:
        lines.append(f"| {row['comparison']} | {row['series']} | {row['metric']} | {row['before_ms']:.3f} | "
                     f"{row['after_ms']:.3f} | {row['change_percent']:+.2f}% | {'yes' if row['over_threshold'] else 'no'} |")
    for title, key in (("Repeated regressions", "reproducible_regressions"),
                       ("Single-series regressions: inconclusive", "single_series_regressions")):
        if report[key]:
            lines.extend(["", f"{title}: " + ", ".join(report[key])])
    if report["unknown_metadata_warnings"]:
        lines.extend(["", "Unknown metadata:"])
        lines.extend(f"- {warning}" for warning in report["unknown_metadata_warnings"])
    lines.extend(["", report["provenance_note"]])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--format", choices=("json", "markdown"), default="markdown")
    args = parser.parse_args()
    try:
        report = evaluate(args.manifest)
    except (OSError, ValueError) as exc:
        print(f"Invalid Compose Styles performance evidence: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2, allow_nan=False) if args.format == "json" else render_markdown(report), end="\n")
    return {"PASS": 0, "FAIL": 1, "INCONCLUSIVE": 3}[report["status"]]


if __name__ == "__main__":
    raise SystemExit(main())
