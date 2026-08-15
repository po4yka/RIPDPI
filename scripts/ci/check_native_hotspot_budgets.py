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
    include_children: bool = False


@dataclass(frozen=True)
class HotspotMeasurement:
    path: str
    measured_production_loc: int
    max_production_loc: int
    included_children: tuple[str, ...] = ()


def production_source(text: str) -> str:
    lines: list[str] = []
    skip_next_test_item = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith("#[cfg") and "test" in stripped:
            skip_next_test_item = True
            continue
        if skip_next_test_item:
            skip_next_test_item = False
            if stripped.startswith("mod ") and stripped.endswith(";"):
                continue
            if stripped.startswith(("pub(crate) use ", "pub use ", "use ")):
                continue
            if stripped.startswith("mod ") and stripped.endswith("{"):
                break
        if stripped == "mod tests {":
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
        budget = HotspotBudget(
            path=entry["path"],
            max_production_loc=int(entry["maxProductionLoc"]),
            include_children=bool(entry.get("includeChildren", False)),
        )
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
    is_test_only_module = source_path.stem.endswith(("_tests", "test_fixtures"))
    measured = 0 if is_test_only_module else count_code_lines(production_source(source_text), "rust")
    return HotspotMeasurement(
        path=budget.path,
        measured_production_loc=measured,
        max_production_loc=budget.max_production_loc,
    )


def inherited_child_paths(
    repo_root: Path,
    parent_budget: HotspotBudget,
    explicit_paths: set[str],
) -> list[str]:
    child_dir = (repo_root / parent_budget.path).with_suffix("")
    if not child_dir.is_dir():
        return []

    children: list[str] = []
    for child_path in sorted(child_dir.glob("*.rs")):
        child_relative_path = child_path.relative_to(repo_root).as_posix()
        if child_relative_path in explicit_paths:
            continue
        child_budget = HotspotBudget(
            path=child_relative_path,
            max_production_loc=parent_budget.max_production_loc,
            include_children=True,
        )
        children.append(child_relative_path)
        children.extend(inherited_child_paths(repo_root, child_budget, explicit_paths))
    return children


def measure_hotspots(repo_root: Path, budgets: list[HotspotBudget]) -> list[HotspotMeasurement]:
    explicit_paths = {budget.path for budget in budgets}
    measurements: list[HotspotMeasurement] = []
    measured_paths: set[str] = set()
    for budget in budgets:
        if budget.path in measured_paths:
            continue
        measurement = measure_hotspot(repo_root, budget)
        included_children = inherited_child_paths(repo_root, budget, explicit_paths) if budget.include_children else []
        inherited_production_loc = sum(
            measure_hotspot(
                repo_root,
                HotspotBudget(path=child_path, max_production_loc=budget.max_production_loc),
            ).measured_production_loc
            for child_path in included_children
        )
        measurements.append(
            HotspotMeasurement(
                path=measurement.path,
                measured_production_loc=measurement.measured_production_loc + inherited_production_loc,
                max_production_loc=measurement.max_production_loc,
                included_children=tuple(included_children),
            ),
        )
        measured_paths.add(budget.path)
    return measurements


def format_summary(measurements: list[HotspotMeasurement]) -> str:
    lines = ["Native hotspot production LoC budgets", f"Checked {len(measurements)} files"]
    overages = [item for item in measurements if item.measured_production_loc > item.max_production_loc]
    lines.append(f"Over budget: {len(overages)}")
    for item in measurements:
        status = "OVER" if item.measured_production_loc > item.max_production_loc else "ok"
        inheritance = "" if not item.included_children else f" included-children={len(item.included_children)}"
        lines.append(
            f"  - {item.path} limit={item.max_production_loc} measured={item.measured_production_loc} [{status}]{inheritance}",
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
    measurements = measure_hotspots(repo_root, budgets)

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
