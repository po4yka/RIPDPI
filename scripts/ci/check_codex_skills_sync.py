#!/usr/bin/env python3
"""Verify portable skill mirrors, rule mirrors, and agent name parity."""

from __future__ import annotations

import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def entry_names(directory: Path, suffix: str | None = None) -> set[str]:
    return {
        path.name
        for path in directory.iterdir()
        if not path.name.startswith(".") and (suffix is None or path.name.endswith(suffix))
    }


def check_skill_mirrors() -> list[str]:
    errors: list[str] = []
    canonical = REPO_ROOT / ".agents" / "skills"
    names = {path.parent.name for path in canonical.glob("*/SKILL.md")}
    for relative in (".claude/skills", ".codex/skills", ".github/skills"):
        mirror = REPO_ROOT / relative
        mirror_names = entry_names(mirror)
        for name in sorted(names - mirror_names):
            errors.append(f"{relative}/{name}: missing canonical skill mirror")
        for name in sorted(mirror_names - names):
            errors.append(f"{relative}/{name}: no canonical .agents/skills entry")
        for name in sorted(names & mirror_names):
            path = mirror / name
            if not path.is_symlink():
                errors.append(f"{relative}/{name}: must be a symlink")
                continue
            try:
                actual = path.resolve(strict=True)
                expected = (canonical / name).resolve(strict=True)
            except (FileNotFoundError, RuntimeError) as exc:
                errors.append(f"{relative}/{name}: broken symlink ({exc})")
                continue
            if actual != expected:
                errors.append(f"{relative}/{name}: resolves to {actual}, expected {expected}")
    return errors


def check_rule_mirror() -> list[str]:
    errors: list[str] = []
    canonical = REPO_ROOT / ".claude" / "rules"
    mirror = REPO_ROOT / ".codex" / "rules"
    canonical_names = entry_names(canonical, ".md")
    mirror_names = entry_names(mirror, ".md")
    for name in sorted(canonical_names - mirror_names):
        errors.append(f".codex/rules/{name}: missing mirror")
    for name in sorted(mirror_names - canonical_names):
        errors.append(f".codex/rules/{name}: orphan mirror")
    for name in sorted(canonical_names & mirror_names):
        path = mirror / name
        if not path.is_symlink() or path.resolve() != (canonical / name).resolve():
            errors.append(f".codex/rules/{name}: must resolve to .claude/rules/{name}")
    return errors


def check_agent_parity() -> list[str]:
    claude = {path.stem for path in (REPO_ROOT / ".claude" / "agents").glob("*.md")}
    codex = {path.stem for path in (REPO_ROOT / ".codex" / "agents").glob("*.toml")}
    return [
        f".codex/agents/{name}.toml: missing Claude counterpart"
        for name in sorted(claude - codex)
    ]


def main() -> int:
    errors = check_skill_mirrors() + check_rule_mirror() + check_agent_parity()
    if errors:
        print("harness compatibility mirrors are out of sync:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print("harness compatibility mirrors in sync.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
