#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

from check_file_loc_limits import count_code_lines


DEFAULT_BUDGET_FILE = "config/static/native-hotspot-production-loc.json"


@dataclass(frozen=True)
class HotspotBudget:
    path: str
    max_production_loc: int


@dataclass(frozen=True)
class HotspotMeasurement:
    path: str
    measured_production_loc: int
    max_production_loc: int


def production_source(text: str) -> str:
    lines: list[str] = []
    for line in text.splitlines():
        if line == "mod tests {":
            break
        lines.append(line)
    return "\n".join(lines) + ("\n" if lines else "")


def load_budgets(path: Path) -> list[HotspotBudget]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    entries = raw.get("entries")
    if not isinstance(entries, list):
        raise ValueError(f"Budget file must contain an entries list: {path}")

    budgets: list[HotspotBudget] = []
    seen_paths: set[str] = set()
    for entry in entries:
        budget = HotspotBudget(path=entry["path"], max_production_loc=int(entry["maxProductionLoc"]))
        if budget.path in seen_paths:
            raise ValueError(f"Duplicate hotspot budget for {budget.path}")
        seen_paths.add(budget.path)
        budgets.append(budget)
    return budgets


def measure_hotspot(repo_root: Path, budget: HotspotBudget) -> HotspotMeasurement:
    relative_path = Path(budget.path)
    source_path = repo_root / relative_path
    if not source_path.is_file():
        raise FileNotFoundError(f"Native hotspot source not found: {budget.path}")

    source_text = source_path.read_text(encoding="utf-8")
    measured = count_code_lines(production_source(source_text), "rust")
    return HotspotMeasurement(
        path=budget.path,
        measured_production_loc=measured,
        max_production_loc=budget.max_production_loc,
    )


def format_summary(measurements: list[HotspotMeasurement]) -> str:
    lines = ["Native hotspot production LoC budgets", f"Checked {len(measurements)} files"]
    overages = [item for item in measurements if item.measured_production_loc > item.max_production_loc]
    lines.append(f"Over budget: {len(overages)}")
    for item in measurements:
        status = "OVER" if item.measured_production_loc > item.max_production_loc else "ok"
        lines.append(
            f"  - {item.path} limit={item.max_production_loc} measured={item.measured_production_loc} [{status}]",
        )
    return "\n".join(lines)


def dump_current(measurements: list[HotspotMeasurement]) -> str:
    payload = {
        "entries": [
            {"path": item.path, "maxProductionLoc": item.measured_production_loc}
            for item in sorted(measurements, key=lambda item: item.path)
        ]
    }
    return json.dumps(payload, indent=2) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description="Verify production-only LoC budgets for native refactor hotspots.")
    parser.add_argument("--budget-file", default=DEFAULT_BUDGET_FILE, help="Checked-in JSON hotspot budget file.")
    parser.add_argument(
        "--dump-current",
        action="store_true",
        help="Print a budget JSON payload using the current measured production LoC values.",
    )
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    budget_path = (repo_root / args.budget_file).resolve()
    budgets = load_budgets(budget_path)
    measurements = [measure_hotspot(repo_root, budget) for budget in budgets]

    if args.dump_current:
        print(dump_current(measurements), end="")
        return 0

    print(format_summary(measurements))
    return 1 if any(item.measured_production_loc > item.max_production_loc for item in measurements) else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"Native hotspot budget verification failed: {exc}", file=sys.stderr)
        raise
