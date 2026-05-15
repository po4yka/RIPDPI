#!/usr/bin/env python3
"""
check_unsafe_boundaries.py -- CI guard against safe APIs wrapping unsafe operations
without explicit, documented preconditions.

POLICY
------
A safe (`pub fn` / `pub(crate) fn`) public surface must not silently extend an unsafe
contract: every unsafe operation reachable from safe code must either be impossible
to misuse (enforced by types/lifetimes/visibility/RAII) or documented and allowlisted
through this guard.

The companion file `scripts/ci/unsafe-boundary-allowlist.toml` records the EXISTING
unsafe-boundary surface snapshotted from main at the time this guard was introduced.
Each entry must include the file, the pattern, why the boundary is sound, who enforces
the preconditions, the owner, and a review date.

The allowlist exists to grandfather existing code while preventing new occurrences.
The correct response to a guard failure is to:
  1) Restructure the API so the unsafe operation cannot be reached unsoundly, OR
  2) Make the public API `unsafe fn` with a precise `# Safety` section, OR
  3) Add an allowlist entry with full justification and assign an owner.

SCOPE
-----
Scans .rs files under native/rust/crates/*/src/**.
Excludes anything inside `tests/`, `benches/`, `examples/`, or matching
`tests.rs` / `test_*.rs`.

EXIT CODES
----------
  0  No new risky patterns outside the allowlist.
  1  At least one new pattern needs justification (see report below).
  2  Allowlist file is malformed.
"""

from __future__ import annotations

import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = REPO_ROOT / "native" / "rust" / "crates"
ALLOWLIST_FILE = REPO_ROOT / "ci" / "unsafe-boundary-allowlist.toml"

# Pattern -> regex. Each pattern flags a class of unsafe operation that, if
# reachable from safe code without local justification, is a soundness risk.
# The regexes are intentionally precise enough to avoid catching unrelated
# identifiers (e.g. `transmute` matches the std `core::mem::transmute` family,
# not a local function named `transmute_payload`).
PATTERNS: dict[str, re.Pattern[str]] = {
    "slice::from_raw_parts": re.compile(r"\b(?:std::|core::)?slice::from_raw_parts(?:_mut)?\b"),
    "Box::from_raw": re.compile(r"\bBox(::<[^>]*>)?::from_raw\b"),
    "Vec::from_raw_parts": re.compile(r"\bVec(::<[^>]*>)?::from_raw_parts\b"),
    "String::from_raw_parts": re.compile(r"\bString::from_raw_parts\b"),
    "MaybeUninit::assume_init": re.compile(r"\.assume_init\b|MaybeUninit::<[^>]*>::assume_init\b"),
    "mem::transmute": re.compile(r"\b(std::|core::)?mem::transmute\b|\btransmute(::<[^>]*>)?\("),
    "get_unchecked": re.compile(r"\.get_unchecked(_mut)?\("),
    "unwrap_unchecked": re.compile(r"\.unwrap_unchecked\(\)"),
    "Pin::new_unchecked": re.compile(r"\bPin::new_unchecked\b"),
    "Pin::get_unchecked_mut": re.compile(r"\.get_unchecked_mut\(\)"),
    # NonNull::as_ref / as_mut: the qualified form is the reliable signal.
    # The unqualified method-call form (`ptr.as_ref()`) collides with the
    # ubiquitous `Option::as_ref` / `&str::as_ref` family, so we only catch
    # the explicit `NonNull::...` spelling here. Raw-pointer dereferences
    # are covered separately by `unsafe_op_in_unsafe_fn = deny` and the
    # SAFETY-comment policy.
    "NonNull::as_ref/as_mut": re.compile(r"\bNonNull(::<[^>]*>)?::as_(ref|mut)\b"),
    "unsafe impl Send/Sync": re.compile(r"^\s*unsafe\s+impl(\s*<[^>]+>)?\s+(Send|Sync)\b"),
    "extern \"C\" fn": re.compile(r"\bextern\s+\"C\"\s+fn\b"),
    "extern \"system\" fn": re.compile(r"\bextern\s+\"system\"\s+fn\b"),
    "NonNull in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*[^;]*\bNonNull\b"
    ),
    "raw pointer in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*[^;]*[:,(\s]\*(const|mut)\s"
    ),
    "raw usize handle in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\b"
        r"(handle|token|raw[_a-z]*)\s*:\s*(u64|i64|usize|isize)\b"
    ),
}

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]
# Strip block comments and line comments before pattern matching so that
# documentation, SAFETY notes, and TODOs don't trigger findings.
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")


@dataclass(frozen=True)
class Finding:
    rel_path: str
    pattern: str
    line: int


def is_excluded(path: Path) -> bool:
    parts = path.parts
    try:
        src_idx = parts.index("src")
    except ValueError:
        return True
    sub_dirs = parts[src_idx + 1 : -1]
    if any(d in EXCLUDE_DIRS for d in sub_dirs):
        return True
    rel = str(path.relative_to(REPO_ROOT))
    return any(pat.search(rel) for pat in EXCLUDE_FILE_RE)


def strip_comments(text: str) -> str:
    text = BLOCK_COMMENT_RE.sub("", text)
    return LINE_COMMENT_RE.sub("", text)


def scan_file(path: Path) -> list[Finding]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_comments(text)
    findings: list[Finding] = []
    for pattern_name, regex in PATTERNS.items():
        for match in regex.finditer(cleaned):
            line = cleaned.count("\n", 0, match.start()) + 1
            findings.append(Finding(rel, pattern_name, line))
    return findings


def collect_findings() -> list[Finding]:
    out: list[Finding] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


def load_allowlist() -> dict[tuple[str, str], dict]:
    if not ALLOWLIST_FILE.exists():
        return {}
    with ALLOWLIST_FILE.open("rb") as fh:
        data = tomllib.load(fh)
    entries = data.get("entries", [])
    out: dict[tuple[str, str], dict] = {}
    required = {"file", "pattern", "reason", "preconditions", "enforcement", "owner", "review_date"}
    for entry in entries:
        missing = required - entry.keys()
        if missing:
            print(
                f"ERROR: allowlist entry is missing fields {sorted(missing)}: {entry}",
                file=sys.stderr,
            )
            sys.exit(2)
        key = (entry["file"], entry["pattern"])
        if key in out:
            print(
                f"ERROR: duplicate allowlist entry for {key}",
                file=sys.stderr,
            )
            sys.exit(2)
        out[key] = entry
    return out


def aggregate_findings(findings: Iterable[Finding]) -> dict[tuple[str, str], list[int]]:
    bucket: dict[tuple[str, str], list[int]] = {}
    for finding in findings:
        bucket.setdefault((finding.rel_path, finding.pattern), []).append(finding.line)
    return bucket


def main() -> int:
    allowlist = load_allowlist()
    findings = collect_findings()
    grouped = aggregate_findings(findings)

    new_violations: list[tuple[tuple[str, str], list[int]]] = []
    stale_allows: list[tuple[str, str]] = []

    seen_keys: set[tuple[str, str]] = set()
    for key, lines in grouped.items():
        seen_keys.add(key)
        if key not in allowlist:
            new_violations.append((key, lines))

    for key in allowlist:
        if key not in seen_keys:
            stale_allows.append(key)

    pattern_total = sum(len(lines) for lines in grouped.values())
    print(
        f"Scanned production Rust under {CRATES_ROOT.relative_to(REPO_ROOT)} -- "
        f"{pattern_total} pattern occurrence(s) across {len(grouped)} (file, pattern) pair(s)."
    )

    if stale_allows:
        print()
        print(f"NOTE: {len(stale_allows)} allowlist entry(ies) no longer match any source -- consider removing:")
        for file_, pattern in stale_allows:
            print(f"  {file_}  pattern={pattern}")

    if not new_violations:
        print(f"\nOK: {len(allowlist)} allowlisted (file, pattern) pair(s) cover all findings.")
        return 0

    print()
    print(f"FAIL: {len(new_violations)} new (file, pattern) pair(s) not covered by the allowlist:")
    for (file_, pattern), lines in sorted(new_violations):
        joined_lines = ", ".join(str(line) for line in lines[:8])
        if len(lines) > 8:
            joined_lines += f", ... ({len(lines)} total)"
        print(f"  {file_}  pattern={pattern}  lines={joined_lines}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Restructure so the unsafe operation cannot be reached from safe code\n"
        "     (newtype, RAII, typestate, BorrowedFd/OwnedFd, etc.).\n"
        "  2) Make the public function `unsafe fn` with a precise `# Safety` section\n"
        "     and propagate the contract to callers.\n"
        "  3) If neither is possible, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with all required fields\n"
        "     (file, pattern, reason, preconditions, enforcement, owner, review_date).\n"
        "\nDo NOT lower lint levels or pass `--allow` to suppress these findings.\n"
        "Policy: docs/rust-soundness-policy.md"
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
