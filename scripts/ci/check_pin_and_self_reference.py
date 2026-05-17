#!/usr/bin/env python3
"""
check_pin_and_self_reference.py -- CI guard: every production use of
manual `Pin` projection or self-referential struct pattern in the
Rust workspace MUST be allowlisted with a documented soundness
argument.

POLICY
------
See docs/rust-soundness-policy.md, sections:
  - "Self-referential structs and Pin"   (audit issue 35)
  - "Incorrect Pin API"                  (audit issue 36)

Manual `Pin` plumbing is the canonical foot-gun: every
`get_unchecked_mut` / `map_unchecked_mut` / `unsafe impl Unpin for`
defeats one of the static guarantees that the rest of the workspace
relies on, and the consequence (a moved self-referential pointer or
a future whose buffer is silently aliased) is usually invisible
under unit tests and only fires under load.

The default position is:

  1. Pin-projection MUST go through `pin-project` / `pin-project-lite`,
     not through manual `unsafe { self.get_unchecked_mut() }`.
  2. Self-reference SHOULD be removed (store an offset/index/owned
     value, allocate the referent separately so its address is
     stable independently of the parent struct).
  3. If a manual pattern is genuinely required, it must appear in
     `ci/pin-allowlist.toml` with the soundness argument written
     out -- what is structurally pinned, why no safe API can move
     it, what `Drop` does, why `Unpin` is or is not implemented.

PATTERNS SCANNED
----------------
Each hit is classified by which sub-pattern triggered, so allowlist
entries can grandfather a specific (file, type, pattern) triple
rather than disabling the guard wholesale.

  * `PhantomPinned`                -- field declaring intent to be !Unpin
  * `get_unchecked_mut`             -- pin-projection escape hatch
  * `map_unchecked_mut`             -- pin-projection escape hatch
  * `Pin::new_unchecked`            -- unchecked pin construction
  * `unsafe impl Unpin for`         -- manual auto-trait override
  * `self_ptr` / `ptr_into_buf` /   -- naming convention for
    `slice_ptr` / `raw_view` /         self-referential pointers
    `cached_ref`

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
ALLOWLIST_FILE = REPO_ROOT / "ci" / "pin-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

# Comment strippers. The marker check uses ORIGINAL text only for
# `Pin safety:` doc-comment markers; the pattern matches run against
# the stripped text so a commented-out `get_unchecked_mut` does not
# falsely trigger.
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

# `macro_rules!` bodies expand to user code at the call site; the
# pattern inside the body is not itself a Pin violation in the
# defining module.
MACRO_RULES_RE = re.compile(r"\bmacro_rules!\s+[A-Za-z_][A-Za-z0-9_]*\s*\{")

# Patterns this guard flags. Each tuple is `(pattern_id, regex)`.
PIN_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("PhantomPinned", re.compile(r"\bPhantomPinned\b")),
    ("get_unchecked_mut", re.compile(r"\.get_unchecked_mut\b")),
    ("map_unchecked_mut", re.compile(r"\.map_unchecked_mut\b")),
    ("Pin::new_unchecked", re.compile(r"\bPin\s*::\s*new_unchecked\b")),
    (
        "unsafe impl Unpin",
        re.compile(r"\bunsafe\s+impl(?:\s*<[^>]*>)?\s+Unpin\s+for\b"),
    ),
    (
        "self_referential_field_name",
        re.compile(
            r"\b(?:self_ptr|ptr_into_buf|slice_ptr|raw_view|cached_ref)\s*:"
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
class PinHit:
    rel_path: str
    pattern_id: str
    line: int
    excerpt: str


def scan_file(path: Path) -> list[PinHit]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        original = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_macro_rules_bodies(strip_comments(original))
    lines = cleaned.splitlines()
    line_offsets = [0]
    for ln in lines:
        line_offsets.append(line_offsets[-1] + len(ln) + 1)
    out: list[PinHit] = []
    for pattern_id, regex in PIN_PATTERNS:
        for m in regex.finditer(cleaned):
            line_no = cleaned.count("\n", 0, m.start()) + 1
            excerpt = lines[line_no - 1].strip() if line_no - 1 < len(lines) else ""
            out.append(
                PinHit(
                    rel_path=rel,
                    pattern_id=pattern_id,
                    line=line_no,
                    excerpt=excerpt[:120],
                )
            )
    return out


def collect() -> list[PinHit]:
    out: list[PinHit] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {
    "file",
    "pattern",
    "reason",
    "soundness_argument",
    "owner",
    "review_date",
}


def load_allowlist() -> dict[tuple[str, str], dict]:
    if not ALLOWLIST_FILE.exists():
        return {}
    with ALLOWLIST_FILE.open("rb") as fh:
        data = tomllib.load(fh)
    entries = data.get("entries", [])
    out: dict[tuple[str, str], dict] = {}
    for entry in entries:
        missing = REQUIRED_ALLOWLIST_FIELDS - entry.keys()
        if missing:
            print(
                f"ERROR: allowlist entry is missing fields {sorted(missing)}: {entry}",
                file=sys.stderr,
            )
            sys.exit(2)
        key = (entry["file"], entry["pattern"])
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
        f"{len(hits)} Pin/self-reference pattern hit(s); "
        f"{len(allowlist)} allowlisted."
    )

    violations: list[PinHit] = []
    seen_allow: set[tuple[str, str]] = set()
    for hit in hits:
        key = (hit.rel_path, hit.pattern_id)
        if key in allowlist:
            seen_allow.add(key)
        else:
            violations.append(hit)

    stale = sorted(set(allowlist.keys()) - seen_allow)
    if stale:
        print()
        print(
            f"NOTE: {len(stale)} allowlist entry(ies) no longer match any "
            "scanned pattern -- consider removing:"
        )
        for file_, pattern in stale:
            print(f"  {file_}::{pattern}")

    if not violations:
        print("\nOK: no unallowlisted Pin/self-reference patterns.")
        return 0

    print()
    print(
        f"FAIL: {len(violations)} production Pin/self-reference pattern hit(s) "
        "lack an allowlist entry:"
    )
    for hit in sorted(violations, key=lambda h: (h.rel_path, h.line)):
        print(f"  {hit.rel_path}:{hit.line}  [{hit.pattern_id}]")
        if hit.excerpt:
            print(f"    {hit.excerpt}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Replace manual pin projection with `pin-project` /\n"
        "     `pin-project-lite`. The crate generates the projection\n"
        "     methods AND statically prevents pin-invariant violations.\n"
        "  2) Remove self-reference -- store an offset/index instead\n"
        "     of a raw pointer; allocate the referent separately so\n"
        "     its address is stable independently of the parent.\n"
        f"  3) If a manual pattern is required, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with a complete\n"
        "     soundness argument (what is structurally pinned, what Drop\n"
        "     does, why no safe API can move the pinned data).\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"Self-referential\n"
        "structs and Pin\" and \"Incorrect Pin API\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
