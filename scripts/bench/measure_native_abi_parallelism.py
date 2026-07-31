#!/usr/bin/env python3
"""Measure the full-ABI Gradle-to-Cargo resource curve."""

from __future__ import annotations

import argparse
import json
import os
import platform
import shlex
import statistics
import subprocess
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FULL_ABIS = "armeabi-v7a,arm64-v8a,x86,x86_64"
DEFAULT_OUTPUT = ROOT / "build/reports/native-build-performance"
RSS_SAMPLE_INTERVAL_SECONDS = 0.2


def gradle_command(parallelism: int, cpu_budget: int | None = None) -> list[str]:
    command = [
        str(ROOT / "gradlew"),
        ":core:engine:buildRustNativeLibs",
        "--no-daemon",
        "--no-build-cache",
        "--no-configuration-cache",
        f"-Pripdpi.localNativeAbis={FULL_ABIS}",
        f"-Pripdpi.nativeAbiParallelism={parallelism}",
    ]
    if cpu_budget is not None:
        command.append(f"-Pripdpi.nativeCpuBudget={cpu_budget}")
    return command


def clean_command() -> list[str]:
    return [
        str(ROOT / "gradlew"),
        ":core:engine:clean",
        "--no-daemon",
        "--no-configuration-cache",
    ]


def aggregate_descendant_rss_bytes(root_pid: int, process_table: str) -> int:
    processes: dict[int, tuple[int, int]] = {}
    for line in process_table.splitlines():
        fields = line.split()
        if len(fields) != 3:
            continue
        pid, parent_pid, rss_kib = (int(field) for field in fields)
        processes[pid] = (parent_pid, rss_kib)

    descendants = {root_pid}
    changed = True
    while changed:
        changed = False
        for pid, (parent_pid, _) in processes.items():
            if parent_pid in descendants and pid not in descendants:
                descendants.add(pid)
                changed = True
    return sum(processes[pid][1] for pid in descendants if pid in processes) * 1024


def process_tree_rss_bytes(root_pid: int) -> int:
    process_table = subprocess.check_output(
        ["ps", "-axo", "pid=,ppid=,rss="], text=True
    )
    return aggregate_descendant_rss_bytes(root_pid, process_table)


def run_measurement(
    *, parallelism: int, run: int, output_dir: Path, cpu_budget: int | None
) -> dict[str, object]:
    subprocess.run(clean_command(), cwd=ROOT, check=True)
    command = gradle_command(parallelism, cpu_budget)
    log_path = output_dir / f"parallelism-{parallelism}-run-{run}.log"
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        peak_rss_bytes = 0
        while process.poll() is None:
            peak_rss_bytes = max(peak_rss_bytes, process_tree_rss_bytes(process.pid))
            time.sleep(RSS_SAMPLE_INTERVAL_SECONDS)
        return_code = process.wait()
    elapsed = time.monotonic() - started
    return {
        "parallelism": parallelism,
        "run": run,
        "elapsedSeconds": round(elapsed, 3),
        "peakRssBytes": peak_rss_bytes,
        "exitCode": return_code,
        "log": str(log_path.relative_to(ROOT)),
    }


def summarize(measurements: list[dict[str, object]]) -> list[dict[str, object]]:
    summaries = []
    for parallelism in sorted({int(item["parallelism"]) for item in measurements}):
        group = [item for item in measurements if item["parallelism"] == parallelism]
        elapsed = [float(item["elapsedSeconds"]) for item in group]
        rss = [int(item["peakRssBytes"]) for item in group if item["peakRssBytes"] is not None]
        summaries.append(
            {
                "parallelism": parallelism,
                "runs": len(group),
                "medianElapsedSeconds": round(statistics.median(elapsed), 3),
                "spreadPercent": round(
                    ((max(elapsed) - min(elapsed)) / statistics.median(elapsed)) * 100,
                    2,
                ),
                "peakRssBytes": max(rss) if rss else None,
                "allSucceeded": all(item["exitCode"] == 0 for item in group),
            }
        )
    return summaries


def total_memory_bytes() -> int | None:
    try:
        return os.sysconf("SC_PAGE_SIZE") * os.sysconf("SC_PHYS_PAGES")
    except (AttributeError, OSError, ValueError):
        return None


def git_output(*args: str) -> str:
    return subprocess.check_output(
        ["git", *args], cwd=ROOT, text=True, stderr=subprocess.DEVNULL
    ).strip()


def write_report(output_dir: Path, report: dict[str, object]) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "native-abi-parallelism.json").write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    lines = [
        "# Native ABI parallelism measurements",
        "",
        "| ABI workers | Runs | Median wall time | Spread | Peak RSS | Success |",
        "|---:|---:|---:|---:|---:|:---:|",
    ]
    for item in report["summary"]:
        rss = item["peakRssBytes"]
        rss_text = f"{rss / 1024 / 1024:.1f} MiB" if rss is not None else "unknown"
        lines.append(
            f"| {item['parallelism']} | {item['runs']} | "
            f"{item['medianElapsedSeconds']:.3f}s | {item['spreadPercent']:.2f}% | "
            f"{rss_text} | {'yes' if item['allSucceeded'] else 'no'} |"
        )
    (output_dir / "native-abi-parallelism.md").write_text(
        "\n".join(lines) + "\n", encoding="utf-8"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--parallelism", nargs="+", type=int, default=[1, 2, 4])
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--cpu-budget", type=int)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if args.runs < 1 or any(value < 1 for value in args.parallelism):
        parser.error("runs and parallelism values must be at least 1")

    if args.dry_run:
        for parallelism in args.parallelism:
            print(shlex.join(clean_command()))
            print(shlex.join(gradle_command(parallelism, args.cpu_budget)))
        return 0

    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    measurements = [
        run_measurement(
            parallelism=parallelism,
            run=run,
            output_dir=output_dir,
            cpu_budget=args.cpu_budget,
        )
        for parallelism in args.parallelism
        for run in range(1, args.runs + 1)
    ]
    report = {
        "schemaVersion": 1,
        "commit": git_output("rev-parse", "HEAD"),
        "dirty": bool(git_output("status", "--porcelain")),
        "host": {
            "platform": platform.platform(),
            "cpuCount": os.cpu_count(),
            "memoryBytes": total_memory_bytes(),
        },
        "fullAbis": FULL_ABIS.split(","),
        "cpuBudget": args.cpu_budget,
        "rssSampleIntervalSeconds": RSS_SAMPLE_INTERVAL_SECONDS,
        "measurements": measurements,
        "summary": summarize(measurements),
    }
    write_report(output_dir, report)
    return 0 if all(item["exitCode"] == 0 for item in measurements) else 1


if __name__ == "__main__":
    raise SystemExit(main())
