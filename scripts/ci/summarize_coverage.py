#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MODULE_THRESHOLDS = {
    "app": 50.0,
    "core:data": 85.0,
    "core:diagnostics": 75.0,
    "core:engine": 70.0,
    "core:service": 70.0,
}
AGGREGATE_THRESHOLD = 65.0
RUST_THRESHOLD = 70.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Summarize Kotlin and Rust coverage artifacts.")
    parser.add_argument("--aggregate-xml", required=True)
    parser.add_argument("--module", action="append", default=[], help="module=path/to/jacoco.xml")
    parser.add_argument("--rust-metrics-env", required=False)
    parser.add_argument("--markdown-output", required=False)
    parser.add_argument("--enforce", action="store_true")
    return parser.parse_args()


def line_coverage_from_xml(path: Path) -> float:
    root = ET.parse(path).getroot()
    for counter in root.findall("counter"):
        if counter.attrib.get("type") == "LINE":
            missed = int(counter.attrib["missed"])
            covered = int(counter.attrib["covered"])
            total = missed + covered
            return 100.0 if total == 0 else covered * 100.0 / total
    raise ValueError(f"No LINE counter found in {path}")


def load_rust_line_coverage(path: Path | None) -> float | None:
    if path is None or not path.exists():
        return None
    for line in path.read_text().splitlines():
        if line.startswith("RUST_LINE_COVERAGE="):
            return float(line.split("=", 1)[1])
    raise ValueError(f"Missing RUST_LINE_COVERAGE in {path}")


def format_row(name: str, coverage: float, threshold: float | None) -> str:
    threshold_label = f"{threshold:.0f}%" if threshold is not None else "n/a"
    return f"| {name} | {coverage:.2f}% | {threshold_label} |"


def main() -> int:
    args = parse_args()
    aggregate_xml = Path(args.aggregate_xml)
    aggregate_coverage = line_coverage_from_xml(aggregate_xml)

    module_rows: list[tuple[str, float, float | None]] = []
    for spec in args.module:
        name, raw_path = spec.split("=", 1)
        path = Path(raw_path)
        module_rows.append((name, line_coverage_from_xml(path), MODULE_THRESHOLDS.get(name)))

    rust_coverage = load_rust_line_coverage(Path(args.rust_metrics_env)) if args.rust_metrics_env else None

    lines = [
        "## Coverage Summary",
        "",
        "| Scope | Line coverage | Threshold |",
        "| --- | ---: | ---: |",
        format_row("Kotlin aggregate", aggregate_coverage, AGGREGATE_THRESHOLD),
    ]
    for name, coverage, threshold in module_rows:
        lines.append(format_row(name, coverage, threshold))
    if rust_coverage is not None:
        lines.append(format_row("Rust aggregate", rust_coverage, RUST_THRESHOLD))
    markdown = "\n".join(lines) + "\n"

    output_path = Path(args.markdown_output) if args.markdown_output else None
    if output_path is not None:
        output_path.write_text(markdown)
    print(markdown)

    if not args.enforce:
        return 0

    failures: list[str] = []
    if aggregate_coverage < AGGREGATE_THRESHOLD:
        failures.append(
            f"Kotlin aggregate line coverage {aggregate_coverage:.2f}% is below {AGGREGATE_THRESHOLD:.2f}%"
        )
    for name, coverage, threshold in module_rows:
        if threshold is not None and coverage < threshold:
            failures.append(f"{name} line coverage {coverage:.2f}% is below {threshold:.2f}%")
    if rust_coverage is not None and rust_coverage < RUST_THRESHOLD:
        failures.append(f"Rust line coverage {rust_coverage:.2f}% is below {RUST_THRESHOLD:.2f}%")

    if failures:
        sys.stderr.write("\n".join(failures) + "\n")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
