#!/usr/bin/env python3
"""
check_codex_skills_sync.py -- CI guard for the .claude / .codex harness mirror.

Three sync rules, one per top-level harness directory:

  .claude/skills  <-->  .codex/skills    bidirectional symlink mirror
  .claude/rules   <-->  .codex/rules     bidirectional symlink mirror
  .claude/agents   -->  .codex/agents    one-way name parity (.codex may have extras)

For the two symlink mirrors:
  * every entry in .claude/<dir> must have a matching entry in .codex/<dir>
  * every entry in .codex/<dir> must be a symlink (never a regular file or
    directory -- a regular copy means content has drifted)
  * every .codex/<dir>/<name> symlink must resolve to .claude/<dir>/<name>
    (compared by resolved absolute path; symlink-target text is not enforced)
  * no .codex/<dir>/<name> symlink may dangle

For agents the codex side intentionally hosts hand-maintained TOML agents that
have no Claude counterpart, so only the .claude -> .codex direction is checked:
every .claude/agents/<name>.md must have a matching .codex/agents/<name>.toml.

EXIT CODES
----------
  0  All mirrors are in sync.
  1  One or more violations were reported (printed to stderr).
"""

from __future__ import annotations

import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]


def _check_symlink_mirror(subdir: str, suffix: str | None) -> list[str]:
    """Check a bidirectional .claude/<subdir> <-> .codex/<subdir> symlink mirror.

    suffix=None  -> entries are directories (skills)
    suffix='.md' -> entries are .md files (rules)
    """
    errors: list[str] = []
    claude_dir = REPO_ROOT / ".claude" / subdir
    codex_dir = REPO_ROOT / ".codex" / subdir

    if not claude_dir.is_dir():
        errors.append(f".claude/{subdir}: directory missing")
        return errors
    if not codex_dir.is_dir():
        errors.append(f".codex/{subdir}: directory missing")
        return errors

    def _names(d: Path) -> set[str]:
        names: set[str] = set()
        for entry in d.iterdir():
            if entry.name.startswith("."):
                continue
            if suffix is None:
                if entry.is_dir() or entry.is_symlink():
                    names.add(entry.name)
            else:
                if entry.name.endswith(suffix):
                    names.add(entry.name)
        return names

    claude_names = _names(claude_dir)
    codex_names = _names(codex_dir)

    for name in sorted(claude_names - codex_names):
        errors.append(f".codex/{subdir}/{name}: missing -- add symlink to ../../.claude/{subdir}/{name}")
    for name in sorted(codex_names - claude_names):
        errors.append(f".codex/{subdir}/{name}: orphan -- no matching .claude/{subdir}/{name}")

    for name in sorted(claude_names & codex_names):
        codex_entry = codex_dir / name
        claude_entry = claude_dir / name

        if not codex_entry.is_symlink():
            errors.append(
                f".codex/{subdir}/{name}: must be a symlink to ../../.claude/{subdir}/{name}, "
                f"found {'directory' if codex_entry.is_dir() else 'regular file'}"
            )
            continue

        try:
            resolved = codex_entry.resolve(strict=True)
        except (FileNotFoundError, RuntimeError) as exc:
            errors.append(f".codex/{subdir}/{name}: dangling symlink ({exc})")
            continue

        try:
            expected = claude_entry.resolve(strict=True)
        except FileNotFoundError:
            errors.append(f".claude/{subdir}/{name}: target missing")
            continue

        if resolved != expected:
            errors.append(
                f".codex/{subdir}/{name}: symlink resolves to {resolved}, "
                f"expected {expected}"
            )

    return errors


def _check_agents_parity() -> list[str]:
    """Every .claude/agents/<name>.md must have a .codex/agents/<name>.toml."""
    errors: list[str] = []
    claude_agents = REPO_ROOT / ".claude" / "agents"
    codex_agents = REPO_ROOT / ".codex" / "agents"

    if not claude_agents.is_dir():
        errors.append(".claude/agents: directory missing")
        return errors
    if not codex_agents.is_dir():
        errors.append(".codex/agents: directory missing")
        return errors

    claude_names = {p.stem for p in claude_agents.iterdir() if p.suffix == ".md" and not p.name.startswith(".")}
    codex_names = {p.stem for p in codex_agents.iterdir() if p.suffix == ".toml" and not p.name.startswith(".")}

    for name in sorted(claude_names - codex_names):
        errors.append(f".codex/agents/{name}.toml: missing -- every .claude/agents/<name>.md needs a Codex counterpart")

    return errors


def main() -> int:
    errors: list[str] = []
    errors.extend(_check_symlink_mirror("skills", suffix=None))
    errors.extend(_check_symlink_mirror("rules", suffix=".md"))
    errors.extend(_check_agents_parity())

    if errors:
        print("codex/claude harness mirror is out of sync:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        print(
            "\nFix: for skills/rules, replace any regular copy with a symlink:\n"
            "  ln -snf ../../.claude/<dir>/<name> .codex/<dir>/<name>\n"
            "For agents, add the matching .codex/agents/<name>.toml.",
            file=sys.stderr,
        )
        return 1

    print("codex/claude harness mirror in sync.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
