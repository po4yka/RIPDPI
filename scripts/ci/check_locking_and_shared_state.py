#!/usr/bin/env python3
"""
check_locking_and_shared_state.py -- CI guard for shared mutable state
anti-patterns surfaced in audit issues 40, 41, 43, 44.

POLICY
------
See docs/rust-soundness-policy.md, sections:
  - "Arc<Mutex<T>> as universal architecture"   (audit issue 40)
  - "Deadlock from nested locks"                (audit issue 41)
  - "Rc<RefCell<T>> as implicit mutable graph"  (audit issue 43)
  - "Rc / Arc cycles"                           (audit issue 44)

This guard catches the two cheapest-to-detect patterns from the
list:

  * `Rc<RefCell<...>>`   -- the canonical issue-43 vector: hides
                            ownership, panics under reentrancy,
                            leaks via cycles. The lower-cost
                            alternatives are owner-tree + Weak,
                            arena + ID, message-passing, immutable
                            snapshot. Production code that needs
                            this pattern MUST allowlist it with a
                            graph-shape and cycle-handling argument.

  * `Rc<Mutex<...>>`     -- the canonical issue-43/44 typo. `Rc`
                            is single-threaded so the lock can
                            never contend; combining the two is
                            almost always evidence of a missing
                            `Arc` somewhere, or a stale refactor.
                            Clippy's `rc_mutex` lint catches this
                            too; restated here so the policy
                            cross-reference is explicit.

For the broader Issue 40 (Arc<Mutex<T>> overuse) and Issue 41
(deadlock from nested locks) we rely on per-PR review augmented by
Clippy's `await_holding_lock` lint (added to
`[workspace.lints.clippy]` in the audit pass for issues 35-49).
A purely structural per-line scan over-fires on a workspace where
`Arc<Mutex<T>>` is the legitimate primary primitive for
multi-producer/multi-consumer shared state.

PATTERNS SCANNED
----------------
  * `Rc<RefCell<...>>`  (and `std::rc::Rc<core::cell::RefCell<...>>`)
  * `Rc<Mutex<...>>`    (and `parking_lot::Mutex` / `tokio::sync::Mutex`)

SCOPE
-----
Scans `.rs` files under `native/rust/crates/*/src/**`. Excludes
`tests/`, `benches/`, `examples/`, `test_*.rs`, `tests.rs`.

EXIT CODES
----------
  0  All hits are allowlisted.
  1  At least one production hit lacks an allowlist entry.
  2  Allowlist file is malformed.
"""

from __future__ import annotations

import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = REPO_ROOT / "native" / "rust" / "crates"
ALLOWLIST_FILE = REPO_ROOT / "ci" / "locking-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
MACRO_RULES_RE = re.compile(r"\bmacro_rules!\s+[A-Za-z_][A-Za-z0-9_]*\s*\{")

# Patterns this guard flags. `Rc<...<RefCell` matches `Rc<RefCell>`,
# `Rc<MyType<RefCell<...>>>`, etc. -- intentionally over-matches.
PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    (
        "Rc<RefCell>",
        re.compile(
            r"\b(?:std::rc::|core::rc::)?Rc\s*<\s*"
            r"(?:[A-Za-z_][\w:]*\s*<\s*)?"  # tolerate one wrapper layer
            r"(?:core::cell::|std::cell::)?RefCell\s*<"
        ),
    ),
    (
        "Rc<Mutex>",
        re.compile(
            r"\b(?:std::rc::|core::rc::)?Rc\s*<\s*"
            r"(?:[A-Za-z_][\w:]*\s*<\s*)?"
            r"(?:std::sync::|parking_lot::|tokio::sync::)?Mutex\s*<"
        ),
    ),
    (
        "Rc<RwLock>",
        re.compile(
            r"\b(?:std::rc::|core::rc::)?Rc\s*<\s*"
            r"(?:[A-Za-z_][\w:]*\s*<\s*)?"
            r"(?:std::sync::|parking_lot::|tokio::sync::)?RwLock\s*<"
        ),
    ),
]


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


def strip_macro_rules_bodies(text: str) -> str:
    out_chars = list(text)
    for match in MACRO_RULES_RE.finditer(text):
        open_brace = match.end() - 1
        depth = 1
        j = open_brace + 1
        while j < len(text) and depth > 0:
            c = text[j]
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            j += 1
        for k in range(open_brace + 1, j - 1):
            if out_chars[k] != "\n":
                out_chars[k] = " "
    return "".join(out_chars)


@dataclass(frozen=True)
class LockHit:
    rel_path: str
    pattern_id: str
    line: int
    excerpt: str


def scan_file(path: Path) -> list[LockHit]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        original = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_macro_rules_bodies(strip_comments(original))
    lines = cleaned.splitlines()
    out: list[LockHit] = []
    for pattern_id, regex in PATTERNS:
        for m in regex.finditer(cleaned):
            line_no = cleaned.count("\n", 0, m.start()) + 1
            excerpt = lines[line_no - 1].strip() if line_no - 1 < len(lines) else ""
            out.append(
                LockHit(
                    rel_path=rel,
                    pattern_id=pattern_id,
                    line=line_no,
                    excerpt=excerpt[:160],
                )
            )
    return out


def collect() -> list[LockHit]:
    out: list[LockHit] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {
    "file",
    "pattern",
    "line",
    "reason",
    "graph_shape",
    "owner",
    "review_date",
}


def load_allowlist() -> dict[tuple[str, str, int], dict]:
    if not ALLOWLIST_FILE.exists():
        return {}
    with ALLOWLIST_FILE.open("rb") as fh:
        data = tomllib.load(fh)
    entries = data.get("entries", [])
    out: dict[tuple[str, str, int], dict] = {}
    for entry in entries:
        missing = REQUIRED_ALLOWLIST_FIELDS - entry.keys()
        if missing:
            print(
                f"ERROR: allowlist entry is missing fields {sorted(missing)}: {entry}",
                file=sys.stderr,
            )
            sys.exit(2)
        key = (entry["file"], entry["pattern"], int(entry["line"]))
        if key in out:
            print(f"ERROR: duplicate allowlist entry for {key}", file=sys.stderr)
            sys.exit(2)
        out[key] = entry
    return out


def main() -> int:
    allowlist = load_allowlist()
    hits = collect()
    print(
        f"Scanned production Rust under {CRATES_ROOT.relative_to(REPO_ROOT)} -- "
        f"{len(hits)} `Rc<RefCell/Mutex/RwLock<...>>` hit(s); "
        f"{len(allowlist)} allowlisted."
    )

    violations: list[LockHit] = []
    seen_allow: set[tuple[str, str, int]] = set()
    for hit in hits:
        key = (hit.rel_path, hit.pattern_id, hit.line)
        if key in allowlist:
            seen_allow.add(key)
        else:
            violations.append(hit)

    stale = sorted(set(allowlist.keys()) - seen_allow)
    if stale:
        print()
        print(
            f"NOTE: {len(stale)} allowlist entry(ies) no longer match any "
            "scanned line -- consider removing or updating the `line` field:"
        )
        for file_, pattern, line in stale:
            print(f"  {file_}:{line} [{pattern}]")

    if not violations:
        print("\nOK: no unallowlisted `Rc<RefCell/Mutex/RwLock>` shared-state patterns.")
        return 0

    print()
    print(
        f"FAIL: {len(violations)} production `Rc<RefCell/Mutex/RwLock>` "
        "hit(s) lack an allowlist entry:"
    )
    for hit in sorted(violations, key=lambda h: (h.rel_path, h.line)):
        print(f"  {hit.rel_path}:{hit.line}  [{hit.pattern_id}]")
        if hit.excerpt:
            print(f"    {hit.excerpt}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Owner-tree + `Weak` back-references for parent/child links.\n"
        "  2) Arena + typed ID for graph structures.\n"
        "  3) Message-passing / command queue for cross-component\n"
        "     mutation.\n"
        "  4) Immutable snapshot (`Arc<T>` swap) for read-heavy state.\n"
        "  5) `Rc<Mutex/RwLock<T>>` -- almost always a typo; did you\n"
        "     mean `Arc<Mutex<T>>` or `Rc<RefCell<T>>`?\n"
        f"  6) If the pattern is genuinely required, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with a complete\n"
        "     `graph_shape` (strong vs Weak edges, cycle handling).\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"Rc<RefCell<T>> as\n"
        "implicit mutable graph\" and \"Rc / Arc cycles\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
