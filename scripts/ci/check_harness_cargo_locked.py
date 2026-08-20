#!/usr/bin/env python3
"""Reject dependency-resolving Cargo commands that can update Cargo.lock."""

from __future__ import annotations

import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCAN_ROOTS = (
    REPO_ROOT / "AGENTS.md",
    REPO_ROOT / "lefthook.yml",
    REPO_ROOT / ".agents",
    REPO_ROOT / ".claude" / "agents",
    REPO_ROOT / ".claude" / "rules",
    REPO_ROOT / ".claude" / "scripts",
    REPO_ROOT / ".codex" / "agents",
    REPO_ROOT / ".github" / "workflows",
    REPO_ROOT / "quality" / "release-gates" / "cross-language-runtime-contracts.json",
    REPO_ROOT / "scripts" / "ci",
)
EXCLUDED_ROOTS = (
    REPO_ROOT / ".agents" / "vendor",
)
TEXT_SUFFIXES = {".json", ".md", ".py", ".sh", ".toml", ".yaml", ".yml"}
SELF = Path(__file__).resolve()
CARGO_COMMAND = re.compile(
    r"cargo\s+\+\S+\s+miri\s+test\b"
    r"|cargo\s+nextest\s+run\b"
    r"|cargo\s+(?:deny|bloat|llvm-lines|flamegraph|llvm-cov)\b"
    r"|cargo(?:\s+\+\S+)?\s+(?:build|bench|check|clippy|doc|fetch|metadata|run|test|tree)\b"
)


def is_excluded(path: Path) -> bool:
    """Return whether path belongs to an independently validated vendor tree."""
    return any(path.is_relative_to(root) for root in EXCLUDED_ROOTS)


def files_to_scan() -> list[Path]:
    paths: list[Path] = []
    for root in SCAN_ROOTS:
        if root.is_file():
            paths.append(root)
        elif root.is_dir():
            paths.extend(
                path
                for path in root.rglob("*")
                if not is_excluded(path) and path.is_file() and path.suffix in TEXT_SUFFIXES
            )
    return sorted({path for path in paths if path.resolve() != SELF})


def unlocked_commands(path: Path) -> list[str]:
    findings: list[str] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        matches = list(CARGO_COMMAND.finditer(line))
        for index, match in enumerate(matches):
            next_start = matches[index + 1].start() if index + 1 < len(matches) else len(line)
            command = line[match.start():next_start]
            if "--locked" not in command:
                findings.append(
                    f"{path.relative_to(REPO_ROOT)}:{line_number}: {command.strip()}"
                )
    return findings


def main() -> int:
    findings = [finding for path in files_to_scan() for finding in unlocked_commands(path)]
    if findings:
        print("HARNESS CARGO LOCK ERRORS", file=sys.stderr)
        for finding in findings:
            print(f"  {finding}", file=sys.stderr)
        return 1
    print("HARNESS CARGO COMMANDS LOCKED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
