#!/usr/bin/env python3
"""Measure the full-ABI Gradle-to-Cargo resource curve."""

from __future__ import annotations

import argparse
import json
import os
import platform
import re
import shlex
import statistics
import subprocess
import sys
import time
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
FULL_ABIS = "armeabi-v7a,arm64-v8a,x86,x86_64"
DEFAULT_OUTPUT = ROOT / "build/reports/native-build-performance"


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


def timed_command(command: list[str]) -> tuple[list[str], re.Pattern[str], int]:
    if sys.platform == "darwin":
        return ["/usr/bin/time", "-l", *command], re.compile(
            r"(?m)^\s*(\d+)\s+maximum resident set size$"
        ), 1
    return ["/usr/bin/time", "-v", *command], re.compile(
        r"(?m)^\s*Maximum resident set size \(kbytes\):\s*(\d+)$"
    ), 1024


def run_measurement(
    *, parallelism: int, run: int, output_dir: Path, cpu_budget: int | None
) -> dict[str, object]:
    subprocess.run(clean_command(), cwd=ROOT, check=True)
    command, rss_pattern, rss_multiplier = timed_command(
        gradle_command(parallelism, cpu_budget)
    )
    log_path = output_dir / f"parallelism-{parallelism}-run-{run}.log"
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.run(
            command,
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=False,
            text=True,
        )
    elapsed = time.monotonic() - started
    log_text = log_path.read_text(encoding="utf-8")
    rss_match = rss_pattern.search(log_text)
    return {
        "parallelism": parallelism,
        "run": run,
        "elapsedSeconds": round(elapsed, 3),
        "peakRssBytes": int(rss_match.group(1)) * rss_multiplier if rss_match else None,
        "exitCode": process.returncode,
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
        "measurements": measurements,
        "summary": summarize(measurements),
    }
    write_report(output_dir, report)
    return 0 if all(item["exitCode"] == 0 for item in measurements) else 1


if __name__ == "__main__":
    raise SystemExit(main())
