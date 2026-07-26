#!/usr/bin/env python3
"""Render or verify docs/tasks/board.md from issue frontmatter."""

from __future__ import annotations

import argparse
import ast
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ISSUES_DIR = ROOT / "docs" / "tasks" / "issues"
BOARD = ROOT / "docs" / "tasks" / "board.md"
STATUS_ORDER = ("doing", "review", "blocked", "todo", "backlog")
STATUS_LABELS = {status: status.title() for status in STATUS_ORDER}
PRIORITY_ORDER = {value: index for index, value in enumerate(("critical", "high", "medium", "low"))}
REQUIRED = ("title", "status", "priority", "area", "owner", "updated")


@dataclass(frozen=True)
class Issue:
    path: Path
    title: str
    status: str
    priority: str
    area: str
    owner: str
    updated: str


def parse_scalar(raw: str) -> str:
    value = raw.strip()
    if value.startswith(("'", '"')):
        parsed = ast.literal_eval(value)
        if not isinstance(parsed, str):
            raise ValueError(f"expected string scalar, got {type(parsed).__name__}")
        return parsed
    return value


def read_issue(path: Path) -> Issue:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or lines[0] != "---":
        raise ValueError(f"{path}: missing opening frontmatter delimiter")
    try:
        end = lines.index("---", 1)
    except ValueError as error:
        raise ValueError(f"{path}: missing closing frontmatter delimiter") from error

    values: dict[str, str] = {}
    for line in lines[1:end]:
        if not line or line[0].isspace() or ":" not in line:
            continue
        key, raw = line.split(":", 1)
        values[key] = parse_scalar(raw)

    missing = [key for key in REQUIRED if not values.get(key)]
    if missing:
        raise ValueError(f"{path}: missing board fields: {', '.join(missing)}")
    if values["status"] not in STATUS_ORDER and values["status"] not in {"done", "dropped"}:
        raise ValueError(f"{path}: unsupported status {values['status']!r}")
    if values["priority"] not in PRIORITY_ORDER:
        raise ValueError(f"{path}: unsupported priority {values['priority']!r}")

    return Issue(
        path=path,
        title=values["title"],
        status=values["status"],
        priority=values["priority"],
        area=values["area"],
        owner=values["owner"],
        updated=values["updated"],
    )


def escape_cell(value: str) -> str:
    return value.replace("|", "\\|")


def render(issues: list[Issue]) -> str:
    lines = [
        "# Task Board",
        "",
        "_Generated from `docs/tasks/issues/*.md` frontmatter. Do not edit by hand; update issue files and regenerate this board._",
        "",
    ]
    for status in STATUS_ORDER:
        rows = [issue for issue in issues if issue.status == status]
        rows.sort(key=lambda issue: (PRIORITY_ORDER[issue.priority], issue.area, issue.title.casefold()))
        lines.extend(
            (
                f"## {STATUS_LABELS[status]} ({len(rows)})",
                "",
                "| Priority | Area | Issue | Owner | Updated |",
                "|---|---|---|---|---|",
            )
        )
        for issue in rows:
            relative = issue.path.relative_to(BOARD.parent).as_posix()
            lines.append(
                "| "
                + " | ".join(
                    (
                        escape_cell(issue.priority),
                        escape_cell(issue.area),
                        f"[{escape_cell(issue.title)}]({relative})",
                        escape_cell(issue.owner),
                        escape_cell(issue.updated),
                    )
                )
                + " |"
            )
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if board.md differs from generated output.")
    args = parser.parse_args()

    try:
        issues = [read_issue(path) for path in sorted(ISSUES_DIR.glob("*.md"))]
        output = render(issues)
    except (OSError, ValueError, SyntaxError) as error:
        print(f"Task board generation failed: {error}", file=sys.stderr)
        return 2

    if args.check:
        current = BOARD.read_text(encoding="utf-8") if BOARD.exists() else ""
        if current != output:
            print("docs/tasks/board.md is stale; run scripts/ci/generate_task_board.py", file=sys.stderr)
            return 1
        print(f"Task board is current ({len(issues)} issue files)")
        return 0

    BOARD.write_text(output, encoding="utf-8")
    print(f"Generated {BOARD.relative_to(ROOT)} from {len(issues)} issue files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
