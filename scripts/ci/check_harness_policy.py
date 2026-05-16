#!/usr/bin/env python3
"""
check_harness_policy.py -- CI guard for drift between the rust-lints SKILL.md
canonical template and the actual native/rust/Cargo.toml + native/rust/clippy.toml.

Algorithm
---------
1. Extract the first ```toml block under each canonical heading from SKILL.md.
2. Parse both blocks with tomllib.
3. Compare every lint level in the canonical set against the project files.
4. Report MISSING_IN_PROJECT and LEVEL_MISMATCH findings.
   EXTRA_IN_PROJECT is intentionally skipped (project may legitimately tighten).

EXIT CODES
----------
  0  Project matches the canonical set (or --report-only is active).
  1  Drift detected.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:
    try:
        import tomli as tomllib  # type: ignore[no-redef]
    except ModuleNotFoundError:
        print("ERROR: tomllib (Python 3.11+) or tomli package required.", file=sys.stderr)
        sys.exit(1)


# ---------------------------------------------------------------------------
# Strength ordering for lint levels
# ---------------------------------------------------------------------------

_LEVEL_STRENGTH: dict[str, int] = {
    "forbid": 4,
    "deny": 3,
    "warn": 2,
    "allow": 1,
}


def _is_weaker(project_level: str, canonical_level: str) -> bool:
    """Return True if project_level is strictly weaker than canonical_level."""
    p = _LEVEL_STRENGTH.get(project_level.lower(), 0)
    c = _LEVEL_STRENGTH.get(canonical_level.lower(), 0)
    return p < c


# ---------------------------------------------------------------------------
# SKILL.md extraction
# ---------------------------------------------------------------------------

_HEADING_WORKSPACE_LINTS = re.compile(
    r"##\s+`\[workspace\.lints\]`\s+[—\-]+\s+canonical template", re.IGNORECASE
)
_HEADING_CLIPPY_TOML = re.compile(
    r"##\s+`clippy\.toml`\s+[—\-]+\s+canonical thresholds", re.IGNORECASE
)
_FENCE_START = re.compile(r"^```toml\s*$")
_FENCE_END = re.compile(r"^```\s*$")


def _extract_first_toml_block_after(lines: list[str], heading_re: re.Pattern[str]) -> str:
    """Find the first ```toml ... ``` block after a heading that matches heading_re."""
    found_heading = False
    in_block = False
    block_lines: list[str] = []

    for line in lines:
        stripped = line.rstrip()
        if not found_heading:
            if heading_re.search(stripped):
                found_heading = True
            continue
        # Inside the section — look for a toml fence
        if not in_block:
            if _FENCE_START.match(stripped):
                in_block = True
            continue
        # Inside the block
        if _FENCE_END.match(stripped):
            return "\n".join(block_lines)
        block_lines.append(line.rstrip())

    raise ValueError(f"Could not find ```toml block after heading matching: {heading_re.pattern!r}")


# ---------------------------------------------------------------------------
# TOML lint-level extraction helpers
# ---------------------------------------------------------------------------

def _flatten_lint_section(table: object) -> dict[str, str]:
    """Flatten a [workspace.lints.*] table to {lint_name: level_str}.

    Handles both plain strings ("deny") and inline tables ({level="deny", priority=-1}).
    """
    if not isinstance(table, dict):
        return {}
    result: dict[str, str] = {}
    for key, val in table.items():
        if isinstance(val, str):
            result[key] = val
        elif isinstance(val, dict) and "level" in val:
            result[key] = str(val["level"])
    return result


def _extract_workspace_lints(toml_text: str) -> dict[str, dict[str, str]]:
    """Parse a workspace-lints TOML block. Returns {section: {lint: level}}."""
    data = tomllib.loads(toml_text)
    workspace = data.get("workspace", data)   # block may or may not have [workspace] wrapper
    lints = workspace.get("lints", {})
    result: dict[str, dict[str, str]] = {}
    for section_name, section_val in lints.items():
        result[section_name] = _flatten_lint_section(section_val)
    return result


def _extract_clippy_toml_scalars(toml_text: str) -> dict[str, object]:
    """Return scalar (non-list, non-dict) entries from clippy.toml for comparison."""
    data = tomllib.loads(toml_text)
    return {k: v for k, v in data.items() if isinstance(v, (int, float, str, bool))}


# ---------------------------------------------------------------------------
# Finding types
# ---------------------------------------------------------------------------

MISSING = "MISSING_IN_PROJECT"
MISMATCH = "LEVEL_MISMATCH"


# ---------------------------------------------------------------------------
# Auditor
# ---------------------------------------------------------------------------

class HarnessPolicyAuditor:
    def __init__(self, root: Path, report_only: bool) -> None:
        self.root = root
        self.report_only = report_only
        self.findings: list[str] = []   # formatted lines, collected and printed together

    def _load_skill_md(self) -> list[str]:
        skill_path = self.root / ".claude" / "skills" / "rust-lints" / "SKILL.md"
        if not skill_path.exists():
            print(f"ERROR: SKILL.md not found: {skill_path}", file=sys.stderr)
            sys.exit(1)
        return skill_path.read_text(encoding="utf-8").splitlines()

    def _load_project_cargo_toml(self) -> dict:
        cargo_path = self.root / "native" / "rust" / "Cargo.toml"
        if not cargo_path.exists():
            print(f"ERROR: Cargo.toml not found: {cargo_path}", file=sys.stderr)
            sys.exit(1)
        return tomllib.loads(cargo_path.read_text(encoding="utf-8"))

    def _load_project_clippy_toml(self) -> dict | None:
        clippy_path = self.root / "native" / "rust" / "clippy.toml"
        if not clippy_path.exists():
            return None
        return tomllib.loads(clippy_path.read_text(encoding="utf-8"))

    # ------------------------------------------------------------------
    # Comparison helpers
    # ------------------------------------------------------------------

    def _compare_workspace_lints(
        self,
        canonical: dict[str, dict[str, str]],
        project_cargo: dict,
    ) -> None:
        project_lints: dict[str, dict[str, str]] = {}
        raw_lints = (
            project_cargo.get("workspace", {}).get("lints", {})
            or project_cargo.get("lints", {})
        )
        for section, table in raw_lints.items():
            project_lints[section] = _flatten_lint_section(table)

        section_findings: dict[str, list[str]] = {}
        for section, canon_keys in canonical.items():
            proj_section = project_lints.get(section, {})
            for lint, canon_level in canon_keys.items():
                proj_level = proj_section.get(lint)
                if proj_level is None:
                    section_findings.setdefault(section, []).append(
                        f"  {lint:<40} canonical: {canon_level:<8} actual: {'<missing>':<12}  ({MISSING})"
                    )
                elif _is_weaker(proj_level, canon_level):
                    section_findings.setdefault(section, []).append(
                        f"  {lint:<40} canonical: {canon_level:<8} actual: {proj_level:<12}  ({MISMATCH})"
                    )

        for section, lines in section_findings.items():
            self.findings.append(f"[workspace.lints.{section}]")
            self.findings.extend(lines)
            self.findings.append("")

    def _compare_clippy_toml_scalars(
        self,
        canonical: dict[str, object],
        project: dict | None,
    ) -> None:
        if project is None:
            # Treat every canonical scalar as missing
            lines = []
            for key, cval in canonical.items():
                lines.append(
                    f"  {key:<40} canonical: {cval!s:<8} actual: {'<missing>':<12}  ({MISSING})"
                )
            if lines:
                self.findings.append("clippy.toml:")
                self.findings.extend(lines)
                self.findings.append("")
            return

        lines = []
        for key, cval in canonical.items():
            pval = project.get(key)
            if pval is None:
                lines.append(
                    f"  {key:<40} canonical: {cval!s:<8} actual: {'<missing>':<12}  ({MISSING})"
                )
            elif isinstance(cval, (int, float)) and isinstance(pval, (int, float)):
                # For thresholds, project using a larger value is weaker (easier to pass)
                if pval > cval:
                    lines.append(
                        f"  {key:<40} canonical: {cval!s:<8} actual: {pval!s:<12}  ({MISMATCH})"
                    )
            elif pval != cval:
                lines.append(
                    f"  {key:<40} canonical: {cval!s:<8} actual: {pval!s:<12}  ({MISMATCH})"
                )
        if lines:
            self.findings.append("clippy.toml:")
            self.findings.extend(lines)
            self.findings.append("")

    # ------------------------------------------------------------------
    # Main entry
    # ------------------------------------------------------------------

    def run(self) -> int:
        skill_lines = self._load_skill_md()

        # Extract canonical blocks from SKILL.md
        try:
            workspace_toml_text = _extract_first_toml_block_after(
                skill_lines, _HEADING_WORKSPACE_LINTS
            )
        except ValueError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

        try:
            clippy_toml_text = _extract_first_toml_block_after(
                skill_lines, _HEADING_CLIPPY_TOML
            )
        except ValueError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 1

        canonical_workspace = _extract_workspace_lints(workspace_toml_text)
        canonical_clippy_scalars = _extract_clippy_toml_scalars(clippy_toml_text)

        project_cargo = self._load_project_cargo_toml()
        project_clippy = self._load_project_clippy_toml()

        self._compare_workspace_lints(canonical_workspace, project_cargo)
        self._compare_clippy_toml_scalars(canonical_clippy_scalars, project_clippy)

        # ------------------------------------------------------------------
        # Output
        # ------------------------------------------------------------------
        print("HARNESS POLICY AUDIT")
        print("====================")
        print(
            "Compared: .claude/skills/rust-lints/SKILL.md vs "
            "native/rust/{Cargo.toml,clippy.toml}"
        )
        print()

        if not self.findings:
            print("CLEAN -- workspace lints match the canonical set.")
            return 0

        # Count non-empty, non-header lines as real findings
        finding_count = sum(
            1 for line in self.findings
            if line.strip() and not line.startswith("[") and not line.startswith("clippy.toml:")
        )
        print(f"DRIFT -- {finding_count} finding(s):\n")
        for line in self.findings:
            print(line)

        if not self.report_only:
            print("exit 1")
            return 1

        return 0


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Compare the canonical rust-lints SKILL.md template against "
            "native/rust/Cargo.toml and native/rust/clippy.toml."
        ),
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
        "--report-only",
        action="store_true",
        help="Print drift findings but exit 0 (advisory mode).",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parents[2]
    auditor = HarnessPolicyAuditor(root=root, report_only=args.report_only)
    return auditor.run()


if __name__ == "__main__":
    sys.exit(main())
