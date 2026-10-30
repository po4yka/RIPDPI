#!/usr/bin/env python3
"""Report which navigation routes the Kaspresso UI journeys reach.

A route counts as covered when any journey source names it as `Route.<Name>`.
The report is informational and always exits 0.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROUTE_FILE = ROOT / "app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt"
JOURNEYS_DIR = ROOT / "app/src/androidTest/kotlin/com/poyka/ripdpi/ui/journeys"
LEAF_ROUTE = re.compile(r"^\s*data (?:object|class) (\w+)\b.*:\s*Route\(\)", re.MULTILINE | re.DOTALL)


def leaf_routes(route_source: str) -> list[str]:
    # A data class header can wrap across lines before `: Route()`, so match each declaration up to its brace.
    names = []
    for declaration in re.split(r"(?=^\s*data (?:object|class) )", route_source, flags=re.MULTILINE):
        match = LEAF_ROUTE.match(declaration.split("{", 1)[0])
        if match:
            names.append(match.group(1))
    return names


def covered_routes(journey_sources: list[str], routes: list[str]) -> set[str]:
    text = "\n".join(journey_sources)
    return {route for route in routes if re.search(rf"\bRoute\.{route}\b", text)}


def render(routes: list[str], covered: set[str]) -> str:
    missing = [route for route in routes if route not in covered]
    lines = [
        "## Kaspresso route coverage",
        "",
        f"{len(covered)} of {len(routes)} routes are reached by a journey.",
        "",
        "Not covered: " + (", ".join(f"`{route}`" for route in missing) if missing else "none"),
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--summary", type=Path, help="Append the Markdown report to this file (GITHUB_STEP_SUMMARY).")
    args = parser.parse_args()

    routes = leaf_routes(ROUTE_FILE.read_text(encoding="utf-8"))
    sources = [path.read_text(encoding="utf-8") for path in sorted(JOURNEYS_DIR.rglob("*.kt"))]
    report = render(routes, covered_routes(sources, routes))
    print(report, end="")
    if args.summary:
        with args.summary.open("a", encoding="utf-8") as summary:
            summary.write(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
