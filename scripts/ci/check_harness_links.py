#!/usr/bin/env python3
"""
check_harness_links.py -- CI guard for dead cross-references in harness files.

Walks canonical skills and local Markdown references, rules, Claude/Codex agents,
AGENTS.md, and CLAUDE.md. Checks harness links and tracked source pointers,
file-qualified declarations, and line coordinates in the current checkout.

EXIT CODES
----------
  0  All verifiable references exist (warn-only refs printed but do not fail).
  1  One or more local references are dead, or --strict is set and
     global-skill references could not be verified.
"""

from __future__ import annotations

import argparse
import ast
import fnmatch
import os
import re
import subprocess
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
_RE_FENCE = re.compile(r"^(`{3,}|~{3,})")

# Concrete source references in inline code or Markdown link destinations.
# File-only short paths may match several files; symbol references must identify one.
SOURCE_EXTENSIONS = ("kt", "kts", "rs", "py", "sh", "proto", "java", "c", "h", "cpp", "js", "ts", "mjs", "pro")
_RE_SOURCE = re.compile(
    r"(?P<path>(?:[\w.*?\[\]-]+/)*[\w*?\[\]-][\w.*?\[\]-]*\.(?:" + "|".join(SOURCE_EXTENSIONS) + r"))"
    r"(?::(?P<line>\d+(?:-\d+)?)|#L(?P<anchor>\d+(?:-L\d+)?)|[:#](?P<symbol>[A-Za-z_]\w*)(?:\(\))?)?$"
)
_RE_INLINE_CODE = re.compile(r"`([^`\n]+)`")
_RE_MARKDOWN_TARGET = re.compile(r"\]\(([^()\s]*(?:\([^()\s]*\)[^()\s]*)*)\)")
_RE_NON_CODE = re.compile(r'//[^\n]*|/\*|(?:br|r)(#*)"|"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\\n])\'', re.DOTALL)


def declaration_code(text: str) -> str:
    """Remove comments and literals before recognizing Rust/Kotlin declarations."""
    result: list[str] = []
    start = 0
    while match := _RE_NON_CODE.search(text, start):
        result.append(text[start:match.start()])
        end = match.end()
        if match.group() == "/*":
            depth = 1
            while depth and end < len(text):
                nested = re.search(r"/\*|\*/", text[end:])
                if nested is None:
                    end = len(text)
                    break
                depth += 1 if nested.group() == "/*" else -1
                end += nested.end()
        elif match.group(1) is not None or match.group() == '"""':
            delimiter = '"' + match.group(1) if match.group(1) is not None else '"""'
            closing = text.find(delimiter, end)
            end = len(text) if closing < 0 else closing + len(delimiter)
        result.append(" ")
        start = end
    result.append(text[start:])
    return "".join(result)


def declares_symbol(path: Path, symbol: str) -> bool:
    text = path.read_text(encoding="utf-8")
    if path.suffix == ".py":
        tree = ast.parse(text)
        return any(
            isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and node.name == symbol
            or isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store) and node.id == symbol
            for node in ast.walk(tree)
        )
    code = declaration_code(text)
    name = re.escape(symbol) + r"\b"
    if path.suffix == ".rs":
        pattern = r"\b(?:fn|struct|enum|trait|type|const|static|mod)\s+(?:mut\s+)?(?:r#)?" + name
    elif path.suffix in {".kt", ".kts"}:
        pattern = r"\b(?:class|interface|object|typealias|val|var)\s+" + name
        pattern += r"|\bfun\s+(?:<[^{};]+>\s*)?(?:[\w<>?.]+\.)?" + name
    elif path.suffix == ".proto":
        pattern = r"\b(?:message|enum|service|rpc)\s+" + name
    else:
        raise ValueError(f"symbol references are supported for Rust, Kotlin, Python, and protobuf, not {path.suffix}")
    return re.search(pattern, code) is not None


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
    reason: str = "NOT FOUND"


# ---------------------------------------------------------------------------
# Core walker
# ---------------------------------------------------------------------------

class HarnessLinkAuditor:
    def __init__(self, root: Path, strict: bool) -> None:
        self.root = root
        self.claude_root = root / ".claude"
        self.vendor_skills = root / ".agents" / "vendor" / "rust-skills" / "skills"
        self.strict = strict
        self.findings: list[Finding] = []
        self.walked = 0
        self.source_references = 0
        self._source_files: list[str] | None = None
        self._source_roots: set[str] = set()
        self._external_source_scopes: dict[str, bool] = {}
        self.external_source_references = 0

    # ------------------------------------------------------------------
    # File collection
    # ------------------------------------------------------------------

    def _collect_files(self) -> list[Path]:
        files: list[Path] = []
        skills_dir = self.root / ".agents" / "skills"
        if skills_dir.is_dir():
            files.extend(sorted(skills_dir.glob("*/SKILL.md")))
            for skill in sorted(skills_dir.iterdir()):
                if skill.is_dir() and not skill.is_symlink():
                    files.extend(sorted(path for path in skill.rglob("*.md") if path.name != "SKILL.md"))
        rules_dir = self.claude_root / "rules"
        if rules_dir.is_dir():
            files.extend(sorted(rules_dir.glob("*.md")))
        agents_dir = self.claude_root / "agents"
        if agents_dir.is_dir():
            files.extend(sorted(agents_dir.glob("*.md")))
        codex_agents = self.root / ".codex" / "agents"
        if codex_agents.is_dir():
            files.extend(sorted(codex_agents.glob("*.toml")))
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

        fence: str | None = None
        for lineno, raw_line in enumerate(text.splitlines(), start=1):
            line = raw_line.strip()

            # Track fenced code blocks — skip pattern matching inside them
            if match := _RE_FENCE.match(line):
                marker = match.group(1)
                if fence is None:
                    fence = marker
                elif marker[0] == fence[0] and len(marker) >= len(fence) and line == marker:
                    fence = None
                continue
            if fence is not None:
                continue

            src = str(path.relative_to(self.root))
            if path.suffix == ".md":
                self._check_skill_refs(raw_line, src, lineno)
                self._check_agent_refs(raw_line, src, lineno)
                if "/references/" not in src:
                    self._check_md_backtick_refs(raw_line, src, lineno)
                self._check_bare_path_refs(raw_line, src, lineno)
            if not self._is_vendored(src):
                self._check_source_refs(raw_line, src, lineno)

    def _tracked_source_files(self) -> list[str]:
        if self._source_files is None:
            environment = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
            environment["GIT_OPTIONAL_LOCKS"] = "0"
            def git(*arguments: str) -> str:
                result = subprocess.run(
                    ["git", "-C", str(self.root), *arguments], env=environment,
                    text=True, capture_output=True, timeout=10,
                )
                if result.returncode:
                    raise ValueError(f"cannot inspect source inventory: git exited {result.returncode}")
                return result.stdout
            if Path(git("rev-parse", "--show-toplevel").strip()).resolve() != self.root.resolve():
                raise ValueError("source inventory root must be the current repository checkout")
            indexed = git("ls-files", "--cached", "-z").rstrip("\0").split("\0")
            self._source_roots = {name.split("/")[0] for name in indexed if "/" in name}
            self._source_files = [
                name for name in indexed
                if _RE_SOURCE.fullmatch(name) and (self.root / name).is_file()
                and (self.root / name).resolve().is_relative_to(self.root.resolve())
            ]
        return self._source_files

    def _check_source_refs(self, line: str, src: str, lineno: int) -> None:
        references = [
            *((reference, False) for reference in _RE_INLINE_CODE.findall(line)),
            *((reference, True) for reference in _RE_MARKDOWN_TARGET.findall(line)),
        ]
        for reference, markdown in dict.fromkeys(references):
            match = _RE_SOURCE.fullmatch(reference)
            if match is None:
                continue
            ref, symbol, line_range, anchor = match.group("path", "symbol", "line", "anchor")
            coordinates = line_range or anchor
            # Sibling repositories and authored template placeholders have no local source target.
            if markdown:
                resolved = (self.root / Path(src).parent / ref).resolve()
                if resolved.is_relative_to(self.root.resolve()):
                    ref = resolved.relative_to(self.root.resolve()).as_posix()
                else:
                    self.external_source_references += 1
                    continue
            elif ref.startswith(("../", "/")):
                self.external_source_references += 1
                continue
            try:
                files = self._tracked_source_files()
                pattern = ref.removeprefix("./").replace("...", "*")
                local = (Path(src).parent / pattern).as_posix()
                rooted = pattern.split("/")[0] in self._source_roots
                if not markdown and not rooted and self._has_external_source_scope(src):
                    self.external_source_references += 1
                    continue
                self.source_references += 1
                if markdown:
                    targets = [ref] if ref in files else []
                else:
                    targets = sorted({
                        name for name in files
                        if fnmatch.fnmatchcase(name, pattern) or fnmatch.fnmatchcase(name, local)
                        or not rooted and fnmatch.fnmatchcase(name, "*/" + pattern)
                    })
                reason = "NOT FOUND in tracked checkout sources"
                if targets and symbol is None and coordinates is None:
                    continue
                if len(targets) == 1:
                    if coordinates is not None:
                        bounds = [int(number) for number in coordinates.replace("L", "").split("-")]
                        total = len((self.root / targets[0]).read_text(encoding="utf-8").splitlines())
                        if 1 <= bounds[0] <= bounds[-1] <= total:
                            continue
                        reason = f"LINE OUT OF RANGE in {targets[0]} ({total} lines)"
                    elif symbol is not None:
                        if declares_symbol(self.root / targets[0], symbol):
                            continue
                        reason = "SYMBOL NOT DECLARED in " + targets[0]
                elif len(targets) > 1:
                    reason = "AMBIGUOUS source; use a full path: " + ", ".join(targets)
            except (OSError, ValueError, SyntaxError, subprocess.TimeoutExpired) as error:
                reason = str(error)
            self.findings.append(Finding("DEAD", src, lineno, reference, reference, reason))

    def _has_external_source_scope(self, src: str) -> bool:
        parts = Path(src).parts
        if parts[:2] != (".agents", "skills"):
            return False
        skill = parts[2]
        if skill not in self._external_source_scopes:
            text = (self.root / ".agents/skills" / skill / "SKILL.md").read_text(encoding="utf-8")
            self._external_source_scopes[skill] = "<!-- harness-source-scope: external -->" in text.splitlines()
        return self._external_source_scopes[skill]

    def _is_vendored(self, src: str) -> bool:
        return (self.root / src).resolve().is_relative_to(self.vendor_skills.resolve())

    def _check_skill_refs(self, line: str, src: str, lineno: int) -> None:
        for m in _RE_SKILL_NAME.finditer(line):
            skill_name = m.group(1)
            local_path = self.root / ".agents" / "skills" / skill_name / "SKILL.md"
            rel = f".agents/skills/{skill_name}/SKILL.md"
            if local_path.exists():
                continue
            # A central skill names its siblings conditionally ("when it is
            # installed"). A sibling in EXCLUDED_VENDOR_SKILLS still resolves
            # in the pinned catalog, so the reference is verified, not dead.
            if self._is_vendored(src) and (self.vendor_skills / skill_name / "SKILL.md").is_file():
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
        print(f"Checked: {self.source_references} source references")
        print(f"External source pointers (outside this checkout): {self.external_source_references}")
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
                print(f"    status:    {f.reason}")
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
        description="Audit harness links, tracked source references, declarations, and line coordinates.",
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
