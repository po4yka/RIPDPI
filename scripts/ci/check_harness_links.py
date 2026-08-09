#!/usr/bin/env python3
"""
check_harness_links.py -- CI guard for dead cross-references in harness files.

Walks canonical .agents/skills/*/SKILL.md, .claude/rules/*.md,
.claude/agents/*.md, AGENTS.md, and CLAUDE.md and
verifies that every extractable cross-reference resolves to a real file on disk.

EXIT CODES
----------
  0  All verifiable references exist (warn-only refs printed but do not fail).
  1  One or more .claude-local references are dead, or --strict is set and
     global-skill references could not be verified.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


# ---------------------------------------------------------------------------
# Pattern definitions
# ---------------------------------------------------------------------------

# Pattern 1: `skill-name` skill / Skill / SKILL  (backtick-quoted, kebab, then word)
_RE_SKILL_NAME = re.compile(r"`([a-z][a-z0-9]*(?:-[a-z0-9][a-z0-9]*)+)`\s{0,3}(?:skill|Skill|SKILL\.md)", re.ASCII)

# Pattern 2: `agent-name` agent
_RE_AGENT_NAME = re.compile(r"`([a-z][a-z0-9]*(?:-[a-z0-9][a-z0-9]*)+)`\s{0,3}agent", re.ASCII)

# Pattern 3: backtick-quoted *.md filename that looks like a rules file.
# Only lowercase-kebab-case names (e.g. `rds-spec.md`) qualify.
# All-caps or mixed-case project-root files (AGENTS.md, CLAUDE.md, SKILL.md,
# README.md, ARCHITECTURE.md, etc.) are prose mentions, not harness references,
# and are intentionally excluded by the [a-z] anchor on the first character.
_RE_MD_BACKTICK = re.compile(r"`([a-z][a-z0-9_-]*\.md)`", re.ASCII)
_PLANNING_ARTIFACT_FILENAMES = frozenset(
    ("proposal.md", "spec.md", "design.md", "tasks.md", "verification.md")
)

# Pattern 4: bare .claude/-prefixed paths
_RE_BARE_PATH = re.compile(r"(?<![`\w])\.claude/(?:skills/[^/\s`\"']+/SKILL\.md|rules/[^/\s`\"']+\.md|agents/[^/\s`\"']+\.md)", re.ASCII)

# Detect fenced code block boundaries
_RE_FENCE_OPEN  = re.compile(r"^```")
_RE_FENCE_CLOSE = re.compile(r"^```")


# ---------------------------------------------------------------------------
# Data types
# ---------------------------------------------------------------------------

@dataclass
class Finding:
    level: str          # "DEAD" or "WARN"
    source_file: str
    line_no: int
    reference: str
    expected_path: str


# ---------------------------------------------------------------------------
# Core walker
# ---------------------------------------------------------------------------

class HarnessLinkAuditor:
    def __init__(self, root: Path, strict: bool) -> None:
        self.root = root
        self.claude_root = root / ".claude"
        self.strict = strict
        self.findings: list[Finding] = []
        self.walked = 0

    # ------------------------------------------------------------------
    # File collection
    # ------------------------------------------------------------------

    def _collect_files(self) -> list[Path]:
        files: list[Path] = []
        skills_dir = self.root / ".agents" / "skills"
        if skills_dir.is_dir():
            files.extend(sorted(skills_dir.glob("*/SKILL.md")))
        rules_dir = self.claude_root / "rules"
        if rules_dir.is_dir():
            files.extend(sorted(rules_dir.glob("*.md")))
        agents_dir = self.claude_root / "agents"
        if agents_dir.is_dir():
            files.extend(sorted(agents_dir.glob("*.md")))
        for name in ("AGENTS.md", "CLAUDE.md"):
            path = self.root / name
            if path.is_file():
                files.append(path)
        return files

    # ------------------------------------------------------------------
    # Per-file analysis
    # ------------------------------------------------------------------

    def _audit_file(self, path: Path) -> None:
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return

        in_fence = False
        for lineno, raw_line in enumerate(text.splitlines(), start=1):
            line = raw_line.strip()

            # Track fenced code blocks — skip pattern matching inside them
            if _RE_FENCE_OPEN.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue

            src = str(path.relative_to(self.root))
            self._check_skill_refs(raw_line, src, lineno)
            self._check_agent_refs(raw_line, src, lineno)
            self._check_md_backtick_refs(raw_line, src, lineno)
            self._check_bare_path_refs(raw_line, src, lineno)

    def _check_skill_refs(self, line: str, src: str, lineno: int) -> None:
        for m in _RE_SKILL_NAME.finditer(line):
            skill_name = m.group(1)
            local_path = self.root / ".agents" / "skills" / skill_name / "SKILL.md"
            rel = f".agents/skills/{skill_name}/SKILL.md"
            if local_path.exists():
                continue
            # Skill not in canonical project skills — treat as global (WARN, not DEAD)
            self.findings.append(Finding(
                level="WARN",
                source_file=src,
                line_no=lineno,
                reference=f"`{skill_name}` skill",
                expected_path=rel,
            ))

    def _check_agent_refs(self, line: str, src: str, lineno: int) -> None:
        for m in _RE_AGENT_NAME.finditer(line):
            agent_name = m.group(1)
            local_path = self.claude_root / "agents" / f"{agent_name}.md"
            rel = f".claude/agents/{agent_name}.md"
            if local_path.exists():
                continue
            self.findings.append(Finding(
                level="DEAD",
                source_file=src,
                line_no=lineno,
                reference=f"`{agent_name}` agent",
                expected_path=rel,
            ))

    def _check_md_backtick_refs(self, line: str, src: str, lineno: int) -> None:
        for m in _RE_MD_BACKTICK.finditer(line):
            filename = m.group(1)
            if (
                src.startswith(".agents/skills/")
                and filename in _PLANNING_ARTIFACT_FILENAMES
            ):
                continue
            # Only interpret as a rules reference when it's not already caught
            # by the bare-path pattern and looks like a rules file.
            local_path = self.claude_root / "rules" / filename
            if not local_path.exists():
                self.findings.append(Finding(
                    level="DEAD",
                    source_file=src,
                    line_no=lineno,
                    reference=filename,
                    expected_path=f".claude/rules/{filename}",
                ))

    def _check_bare_path_refs(self, line: str, src: str, lineno: int) -> None:
        for m in _RE_BARE_PATH.finditer(line):
            rel_ref = m.group(0)        # e.g. ".claude/rules/foo.md"
            target = self.root / rel_ref
            if not target.exists():
                self.findings.append(Finding(
                    level="DEAD",
                    source_file=src,
                    line_no=lineno,
                    reference=rel_ref,
                    expected_path=rel_ref,
                ))

    # ------------------------------------------------------------------
    # Main entry
    # ------------------------------------------------------------------

    def run(self) -> int:
        files = self._collect_files()
        self.walked = len(files)
        for path in files:
            self._audit_file(path)

        dead = [f for f in self.findings if f.level == "DEAD"]
        warns = [f for f in self.findings if f.level == "WARN"]

        print("HARNESS LINK AUDIT")
        print("==================")
        print(f"Walked: {self.walked} canonical harness files")
        print()

        if warns:
            print(f"GLOBAL-SKILL REFS (cannot verify from CI) -- {len(warns)} warning(s):\n")
            for f in warns:
                print(f"  {f.source_file}:{f.line_no}")
                print(f"    reference: {f.reference}")
                print(f"    expected:  {f.expected_path}")
                print("    status:    NOT IN LOCAL .agents/skills/ (may be a global skill)")
                print()

        if not dead and (not warns or not self.strict):
            print("CLEAN -- 0 dead references")
            return 0

        if dead:
            print(f"DEAD REFERENCES -- {len(dead)} finding(s):\n")
            for f in dead:
                print(f"  {f.source_file}:{f.line_no}")
                print(f"    reference: {f.reference}")
                print(f"    expected:  {f.expected_path}")
                print("    status:    NOT FOUND")
                print()
            print("exit 1")
            return 1

        # strict mode: warns become failures
        if self.strict and warns:
            print("exit 1  (--strict: global-skill references treated as failures)")
            return 1

        return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Audit cross-references in .claude harness files for dead links.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument(
        "--root",
        metavar="REPO_ROOT",
        default=None,
        help="Repository root (default: two directories above this script).",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="Treat unverifiable global-skill references as failures (exit 1).",
    )
    parser.add_argument(
        "--report-only",
        action="store_true",
        help="Print findings but always exit 0 (advisory mode for transitional periods).",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[2]
    auditor = HarnessLinkAuditor(root=root, strict=args.strict)
    exit_code = auditor.run()
    if args.report_only and exit_code != 0:
        print("\n[advisory] --report-only: findings detected but exit code forced to 0", file=sys.stderr)
        return 0
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
