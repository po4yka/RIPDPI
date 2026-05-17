#!/usr/bin/env python3
"""
check_raw_slice_and_layout.py -- CI guard: every production use of
`slice::from_raw_parts` / `slice::from_raw_parts_mut` and every
manual `size_of::<T>()` multiplication in allocation/layout code
MUST be allowlisted with a documented soundness argument.

POLICY
------
See docs/rust-soundness-policy.md, sections:
  - "Raw slice construction is a soundness boundary"  (audit issue 47)
  - "Allocation / layout arithmetic"                  (audit issue 48)

Raw slice construction takes a pointer + length and synthesizes a
Rust reference whose lifetime is bounded only by the caller's
promise. Every contract listed in `slice::from_raw_parts`'s
`# Safety` section -- pointer non-null and aligned, memory
initialized for `len` elements, full range inside one allocation,
length expressible as bytes without overflow, exclusive access for
the `_mut` variant -- is a separate failure mode, all of them
silent under unit tests.

Manual `count * size_of::<T>()` arithmetic for allocation sizes is
the canonical issue-48 vector: when `count` is attacker-controlled
(network length field, parser tag, `as usize` from a foreign
integer), the multiplication overflows to a small value, the
allocator returns a buffer smaller than the caller expected, and
the next write or read goes out of bounds.

The default position is:

  1. Internal APIs should take `&[T]` / `&mut [T]`, NOT pointer +
     length. Conversion at the FFI boundary happens once with a
     validated owner.
  2. Allocation/layout math should use `Layout::array::<T>(count)`,
     `checked_mul`, `try_reserve`, or `usize::try_from` -- never
     bare `count * size_of::<T>()`.
  3. If a raw-pointer pattern is genuinely required, it must
     appear in `ci/raw-slice-layout-allowlist.toml` with the
     soundness argument written out.

PATTERNS SCANNED
----------------
  * `slice::from_raw_parts`         (and `core::slice::...`)
  * `slice::from_raw_parts_mut`     (and `core::slice::...`)
  * `Layout::from_size_align(`      (alignment+size pair NOT going
                                     through `Layout::array`)
  * `* std::mem::size_of::<`        (manual byte-size multiplication)
  * `size_of::<T>() *`              (same, other operand order)

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
ALLOWLIST_FILE = REPO_ROOT / "ci" / "raw-slice-layout-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
MACRO_RULES_RE = re.compile(r"\bmacro_rules!\s+[A-Za-z_][A-Za-z0-9_]*\s*\{")

# (pattern_id, regex). The patterns intentionally over-match; the
# allowlist holds the per-call-site justification.
PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    (
        "from_raw_parts",
        re.compile(r"\b(?:slice|core::slice|std::slice)::from_raw_parts\b(?!_mut)"),
    ),
    (
        "from_raw_parts_mut",
        re.compile(r"\b(?:slice|core::slice|std::slice)::from_raw_parts_mut\b"),
    ),
    (
        "Layout::from_size_align",
        re.compile(r"\bLayout\s*::\s*from_size_align(?:_unchecked)?\b"),
    ),
    (
        "size_of_times_count",
        re.compile(
            r"(?:\*\s*(?:std::|core::|::)?mem::size_of\s*::\s*<)|"
            r"(?:\bsize_of\s*::\s*<[^>]+>\s*\(\s*\)\s*\*)"
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
class RawHit:
    rel_path: str
    pattern_id: str
    line: int
    excerpt: str


def scan_file(path: Path) -> list[RawHit]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        original = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_macro_rules_bodies(strip_comments(original))
    lines = cleaned.splitlines()
    out: list[RawHit] = []
    for pattern_id, regex in PATTERNS:
        for m in regex.finditer(cleaned):
            line_no = cleaned.count("\n", 0, m.start()) + 1
            excerpt = lines[line_no - 1].strip() if line_no - 1 < len(lines) else ""
            out.append(
                RawHit(
                    rel_path=rel,
                    pattern_id=pattern_id,
                    line=line_no,
                    excerpt=excerpt[:160],
                )
            )
    return out


def collect() -> list[RawHit]:
    out: list[RawHit] = []
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
    "soundness_argument",
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
        f"{len(hits)} raw-slice/layout pattern hit(s); "
        f"{len(allowlist)} allowlisted."
    )

    violations: list[RawHit] = []
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
        print("\nOK: no unallowlisted raw-slice / layout-arithmetic call sites.")
        return 0

    print()
    print(
        f"FAIL: {len(violations)} production raw-slice / layout-arithmetic hit(s) "
        "lack an allowlist entry:"
    )
    for hit in sorted(violations, key=lambda h: (h.rel_path, h.line)):
        print(f"  {hit.rel_path}:{hit.line}  [{hit.pattern_id}]")
        if hit.excerpt:
            print(f"    {hit.excerpt}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Accept safe `&[T]` / `&mut [T]` at internal APIs; convert\n"
        "     once at the FFI boundary with a validated owner.\n"
        "  2) For layout math, use `Layout::array::<T>(count)`,\n"
        "     `checked_mul`, `try_reserve`, or `usize::try_from`.\n"
        f"  3) If a raw pattern is required, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with a complete\n"
        "     `soundness_argument` (pointer validity, alignment,\n"
        "     initialization, lifetime owner, aliasing, overflow).\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"Raw slice construction\n"
        "is a soundness boundary\" and \"Allocation / layout arithmetic\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
