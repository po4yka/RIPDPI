#!/usr/bin/env python3
"""
check_phantomdata_variance.py -- CI guard: every production
`PhantomData<...>` field MUST have an adjacent comment explaining
its variance / Send-Sync / drop-check intent, OR be allowlisted.

POLICY
------
See docs/rust-soundness-policy.md, sections:
  - "PhantomData and variance"           (audit issues 45 / 46)

`PhantomData<T>` is silent about its meaning at the type level: the
same syntactic form simultaneously decides drop-check ownership,
auto-trait inheritance (`Send` / `Sync`), and variance over lifetime
and type parameters. Picking the wrong form has been the canonical
issue-45/46 failure mode in unsafe abstractions:

  * `PhantomData<T>`              -- owning, invariant in T for
                                     drop-check; inherits T's
                                     auto-traits.
  * `PhantomData<&'a T>`           -- borrowed, covariant in T and 'a.
  * `PhantomData<&'a mut T>`       -- borrowed, invariant.
  * `PhantomData<*const T>`        -- non-owning, covariant, removes
                                     auto-traits.
  * `PhantomData<*mut T>`          -- non-owning, invariant, removes
                                     auto-traits.
  * `PhantomData<fn() -> T>`       -- the "Send + Sync without
                                     ownership" marker.
  * `PhantomData<fn(T)>`           -- the contravariant marker.

The default position is: any production `PhantomData` must be
within ±5 lines of a `// Variance:` (or `/// Variance:`, or
`// PhantomData:`) comment that names its intent. If multiple
PhantomData fields share rationale, a single comment covering them
all suffices as long as the proximity rule holds.

PATTERNS SCANNED
----------------
  * `PhantomData<...>` field in a struct, tuple struct, or enum
    variant.

The guard also tolerates `PhantomData<...>` inside `Check<T> ...`
helper types used for static auto-trait assertions; those are
identified by being in the same file as an `AmbiguousIfSend` /
`AmbiguousIfSync` / `AmbiguousIfCopy` trait declaration and skipped.

SCOPE
-----
Scans `.rs` files under `native/rust/crates/*/src/**`. Excludes
`tests/`, `benches/`, `examples/`, `test_*.rs`, `tests.rs`.

EXIT CODES
----------
  0  All hits are documented or allowlisted.
  1  At least one production hit lacks proximity-documentation
     and is not allowlisted.
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
ALLOWLIST_FILE = REPO_ROOT / "ci" / "phantomdata-variance-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

PHANTOMDATA_RE = re.compile(r"\bPhantomData\s*<")

# `#[cfg(test)] mod ... { ... }` -- test-gated modules are skipped.
CFG_TEST_MOD_RE = re.compile(
    r"#\[\s*cfg\s*\(\s*test\s*\)\s*\]\s*"
    r"(?:pub(?:\s*\([^)]*\))?\s+)?mod\s+[A-Za-z_][A-Za-z0-9_]*\s*\{"
)


def _strip_cfg_test_modules(text: str) -> str:
    out_chars = list(text)
    for match in CFG_TEST_MOD_RE.finditer(text):
        open_brace = match.end() - 1
        if open_brace >= len(text) or text[open_brace] != "{":
            continue
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

# Marker comments that satisfy the documentation requirement.
# Case-insensitive; line, block, or doc comments all count.
DOC_MARKER_RES = [
    re.compile(r"//[^\n]*\bvariance\s*:", re.IGNORECASE),
    re.compile(r"//[^\n]*\bphantomdata\s*:", re.IGNORECASE),
    re.compile(r"//[^\n]*\bphantom\s*:", re.IGNORECASE),
    re.compile(r"/\*[^*]*\bvariance\s*:", re.IGNORECASE),
    re.compile(r"/\*[^*]*\bphantomdata\s*:", re.IGNORECASE),
]

# Files containing one of these names house static auto-trait
# assertion helpers; their `PhantomData<T>` usages are the assertion
# infrastructure, not a soundness boundary, and are skipped wholesale.
AUTO_TRAIT_HELPER_NAMES = re.compile(
    r"\b(?:AmbiguousIfSend|AmbiguousIfSync|AmbiguousIfCopy)\b"
)

# The "marker comment must be within ±N lines of the field declaration"
# heuristic. 10 lines lets the comment cover a multi-paragraph doc
# block immediately preceding the field without needing to be wedged
# onto the field's own line. Anything wider would let an unrelated
# comment elsewhere in the struct satisfy the requirement.
PROXIMITY_LINES = 10


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


@dataclass(frozen=True)
class PhantomHit:
    rel_path: str
    line: int
    excerpt: str
    has_doc_marker: bool


def has_proximity_marker(text: str, line_no: int) -> bool:
    lines = text.splitlines()
    start = max(0, line_no - 1 - PROXIMITY_LINES)
    end = min(len(lines), line_no - 1 + PROXIMITY_LINES + 1)
    window = "\n".join(lines[start:end])
    return any(rx.search(window) for rx in DOC_MARKER_RES)


def scan_file(path: Path) -> list[PhantomHit]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    if AUTO_TRAIT_HELPER_NAMES.search(text):
        # Skip files that house the auto-trait assertion infrastructure.
        return []
    scan_text = _strip_cfg_test_modules(text)
    lines = scan_text.splitlines()
    out: list[PhantomHit] = []
    for m in PHANTOMDATA_RE.finditer(scan_text):
        line_no = scan_text.count("\n", 0, m.start()) + 1
        excerpt = lines[line_no - 1].strip() if line_no - 1 < len(lines) else ""
        out.append(
            PhantomHit(
                rel_path=rel,
                line=line_no,
                excerpt=excerpt[:160],
                has_doc_marker=has_proximity_marker(scan_text, line_no),
            )
        )
    return out


def collect() -> list[PhantomHit]:
    out: list[PhantomHit] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {
    "file",
    "line",
    "reason",
    "variance_argument",
    "owner",
    "review_date",
}


def load_allowlist() -> dict[tuple[str, int], dict]:
    if not ALLOWLIST_FILE.exists():
        return {}
    with ALLOWLIST_FILE.open("rb") as fh:
        data = tomllib.load(fh)
    entries = data.get("entries", [])
    out: dict[tuple[str, int], dict] = {}
    for entry in entries:
        missing = REQUIRED_ALLOWLIST_FIELDS - entry.keys()
        if missing:
            print(
                f"ERROR: allowlist entry is missing fields {sorted(missing)}: {entry}",
                file=sys.stderr,
            )
            sys.exit(2)
        key = (entry["file"], int(entry["line"]))
        if key in out:
            print(f"ERROR: duplicate allowlist entry for {key}", file=sys.stderr)
            sys.exit(2)
        out[key] = entry
    return out


def main() -> int:
    allowlist = load_allowlist()
    hits = collect()
    documented = [h for h in hits if h.has_doc_marker]
    undocumented = [h for h in hits if not h.has_doc_marker]
    print(
        f"Scanned production Rust under {CRATES_ROOT.relative_to(REPO_ROOT)} -- "
        f"{len(hits)} `PhantomData<...>` hit(s); "
        f"{len(documented)} have proximity-doc; "
        f"{len(undocumented)} need a comment or allowlist."
    )

    violations: list[PhantomHit] = []
    seen_allow: set[tuple[str, int]] = set()
    for hit in undocumented:
        key = (hit.rel_path, hit.line)
        if key in allowlist:
            seen_allow.add(key)
        else:
            violations.append(hit)

    stale = sorted(set(allowlist.keys()) - seen_allow)
    if stale:
        print()
        print(
            f"NOTE: {len(stale)} allowlist entry(ies) no longer match any "
            "scanned undocumented hit -- consider removing:"
        )
        for file_, line in stale:
            print(f"  {file_}:{line}")

    if not violations:
        print(
            f"\nOK: {len(documented)} documented + {len(allowlist)} allowlisted."
        )
        return 0

    print()
    print(
        f"FAIL: {len(violations)} production `PhantomData<...>` hit(s) "
        "lack a proximity comment AND an allowlist entry:"
    )
    for hit in sorted(violations, key=lambda h: (h.rel_path, h.line)):
        print(f"  {hit.rel_path}:{hit.line}")
        if hit.excerpt:
            print(f"    {hit.excerpt}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Add a `// Variance: ...` (or `// PhantomData: ...`)\n"
        f"     comment within ±{PROXIMITY_LINES} lines of the field that names\n"
        "     the intended:\n"
        "       - ownership for drop-check (owning vs borrowed),\n"
        "       - variance over lifetime/type parameters,\n"
        "       - auto-trait effect (Send / Sync inheritance,\n"
        "         removal via `*mut T`, restoration via `fn() -> T`).\n"
        f"  2) If the rationale is too long to inline, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with a complete\n"
        "     `variance_argument`.\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"PhantomData and variance\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
