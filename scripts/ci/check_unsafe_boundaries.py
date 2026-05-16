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
    # NB: the four `^`-anchored patterns below MUST be compiled with
    # `re.MULTILINE` so `^` matches the start of any line, not only the
    # first character of the whole file. Prior to this fix the patterns
    # silently matched zero occurrences in production code; the test
    # suite's single-line fragments happened to pass because `^` aligns
    # with position 0 of a one-line string.
    "unsafe impl Send/Sync": re.compile(
        r"^\s*unsafe\s+impl(\s*<[^>]+>)?\s+(Send|Sync)\b", re.MULTILINE
    ),
    "extern \"C\" fn": re.compile(r"\bextern\s+\"C\"\s+fn\b"),
    "extern \"system\" fn": re.compile(r"\bextern\s+\"system\"\s+fn\b"),
    # `[^;{]*` (rather than `[^;]*`) constrains the body-of-signature match
    # to characters before the opening `{` of the function body — without
    # this, the greedy class spans across the function body and matches
    # raw-pointer casts in unrelated code further down the file.
    "NonNull in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*[^;{]*\bNonNull\b",
        re.MULTILINE,
    ),
    "raw pointer in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*[^;{]*[:,(\s]\*(const|mut)\s",
        re.MULTILINE,
    ),
    "raw usize handle in public fn": re.compile(
        r"^\s*pub(\s*\([^)]*\))?\s+(unsafe\s+)?fn\s+[A-Za-z_][A-Za-z0-9_]*\s*\([^)]*\b"
        r"(handle|token|raw[_a-z]*)\s*:\s*(u64|i64|usize|isize)\b",
        re.MULTILINE,
    ),
    # Option<NonNull<T>> is Copy. Stored in a struct field, returned from a
    # function, or passed as a parameter, it cannot encode ownership: safe
    # callers may duplicate the value and produce stale handles, UAF, or
    # double-free. The fix is a private move-only newtype around `NonNull`
    # (no Copy/Clone) plus `Option<OwnerHandle<T>>`. See
    # docs/rust-soundness-policy.md § "Option<NonNull<T>> ownership tokens".
    "Option<NonNull<T>>": re.compile(r"\bOption\s*<\s*NonNull\s*<"),
    # The slot-mutating form — `fn extract(slot: &mut Option<NonNull<T>>)`
    # is the most common UAF/double-free vector: the function can `take()`
    # the value but safe code already held a copy of the original slot.
    "&mut Option<NonNull<T>>": re.compile(r"&\s*mut\s+Option\s*<\s*NonNull\s*<"),
    # CStr::from_ptr materializes a `&CStr` whose bytes are scanned for a
    # NUL terminator starting at the raw pointer. The pointee must be a
    # valid NUL-terminated C string in an allocation that lives at least
    # as long as the returned `&CStr`. New occurrences require an
    # allowlist entry naming the source of the validity guarantee
    # (POSIX syscall contract, FFI caller contract, etc.) per
    # docs/rust-soundness-policy.md § "Creating `&T` from raw pointers".
    "CStr::from_ptr": re.compile(r"\bCStr(::<[^>]*>)?::from_ptr\b"),
    # str::from_utf8_unchecked turns `&[u8]` into `&str` without
    # validating UTF-8. A safe-API regression here invalidates the
    # `str` invariant and produces UB on any subsequent UTF-8 operation.
    "str::from_utf8_unchecked": re.compile(r"\b(?:std::|core::)?str::from_utf8_unchecked\b"),
    # UnsafeCell::get returns `*mut T` from `&UnsafeCell<T>`. Dereferencing
    # it to produce `&mut T` (the canonical `unsafe { (*cell.get()).as_mut() }`
    # pattern) bypasses Rust's shared-vs-exclusive borrow check entirely;
    # soundness depends on the caller proving no other accessor exists.
    # New occurrences require an allowlist entry naming the exclusivity
    # discipline (move-only handle + free list, mutex, type-state, etc.)
    # per docs/rust-soundness-policy.md § "Creating `&mut T` from raw
    # memory".
    "UnsafeCell::get": re.compile(r"\.get\(\)"),  # narrowed below
    # `Cell<bool>` is a common cheap way to encode lifecycle state, but
    # the value's mutation has no synchronisation cost and no exclusivity
    # discipline. Use a typestate or RAII guard instead. There are zero
    # production occurrences today; any new appearance must be reviewed
    # and either restructured or earn an allowlist entry whose
    # `enforcement` field explains why ownership/liveness is encoded
    # elsewhere. See docs/rust-soundness-policy.md § "Ownership must be
    # types, not flags".
    "Cell<bool>": re.compile(r"\bCell\s*<\s*bool\s*>"),
}

# The `.get()` method is also used by many safe types (HashMap, Vec,
# Option, AtomicPtr, etc.), so the regex above intentionally matches a
# superset. We refine the match in `_unsafe_cell_get_filter` so only
# `*cell.get()` / `(*cell.get())` / `unsafe { ... .get() ... }` patterns
# co-located with an `UnsafeCell` type signal a real finding.
UNSAFE_CELL_USE_RE = re.compile(r"\bUnsafeCell\b")
UNSAFE_CELL_GET_RE = re.compile(r"\*\s*[A-Za-z_][A-Za-z0-9_\.\[\]]*\.get\(\)")


def _filter_unsafe_cell_get(text: str, candidate_lines: list[int]) -> list[int]:
    """Drop `.get()` matches that are not the `*cell.get()` pattern.

    The regex `\\.get\\(\\)` deliberately over-matches; we only emit a
    finding when:
      - the file mentions `UnsafeCell` at least once (excludes most std
        collection `.get()` callers), AND
      - the matched `.get()` is preceded by a leading `*` deref
        (the only shape that materialises `*mut T` → `&mut T` /
        `&T` from an `UnsafeCell`).
    """
    if not UNSAFE_CELL_USE_RE.search(text):
        return []
    kept: list[int] = []
    deref_lines = {text.count("\n", 0, m.start()) + 1 for m in UNSAFE_CELL_GET_RE.finditer(text)}
    for line in candidate_lines:
        if line in deref_lines:
            kept.append(line)
    return kept

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]
# Strip block comments and line comments before pattern matching so that
# documentation, SAFETY notes, and TODOs don't trigger findings.
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

# Proximity detector for `debug_assert!` near `unsafe`. The single-line
# regex set above cannot express "within N lines of X", so we run a small
# post-pass that matches the two sentinels independently and emits a
# finding for every debug-assertion that sits within
# DEBUG_ASSERT_PROXIMITY_LINES lines of an `unsafe` keyword. The policy
# in docs/rust-soundness-policy.md (§ Mandatory Invariant #3) is that
# `debug_assert!` does NOT count as memory-safety enforcement; this scan
# stops new code from re-introducing the pattern.
DEBUG_ASSERT_RE = re.compile(r"\bdebug_assert(?:_eq|_ne)?!")
UNSAFE_KEYWORD_RE = re.compile(r"\bunsafe\b")
DEBUG_ASSERT_PROXIMITY_LINES = 10
DEBUG_ASSERT_PROXIMITY_PATTERN = "debug_assert near unsafe"

# Proximity detector for ownership-flag bool fields near `impl Drop` or
# `unsafe`. The issue-#11 audit established that ownership must be
# encoded as types (move-only handles, RAII, typestate) rather than
# boolean flags. A field named `registered`, `is_alive`, `destroyed`,
# `initialized`, `disowned`, `owned_by_*`, `freed`, or `active` whose
# value gates an `unsafe` operation or a Drop-time cleanup is a
# classic recipe for double-destroy / stale-handle / aliasing-by-flag
# bugs in release builds.
OWNERSHIP_FLAG_RE = re.compile(
    r"^\s*(?:pub(?:\s*\([^)]*\))?\s+)?"
    r"(?:owned_by_\w+|is_alive|destroyed|registered|initialized|disowned|freed)"
    r"\s*:\s*bool\s*[,;}]",
    re.MULTILINE,
)
DROP_IMPL_RE = re.compile(r"\bimpl(\s*<[^>]+>)?\s+Drop\s+for\b")
OWNERSHIP_FLAG_PROXIMITY_LINES = 50
OWNERSHIP_FLAG_PROXIMITY_PATTERN = "ownership flag near drop/unsafe"


def find_ownership_flag_near_drop_or_unsafe(text: str) -> list[int]:
    """Return line numbers of ownership-flag bool fields within ±N lines of
    an `impl Drop` or `unsafe` keyword in the same file.

    `text` must already have had comments stripped, so doc-comment
    mentions of these names don't fire the rule. The proximity window
    is wider than the debug-assert one (50 lines vs 10) because the
    `impl Drop` for a struct typically lives a struct-body's distance
    away from the bool field declaration.
    """
    flag_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in OWNERSHIP_FLAG_RE.finditer(text)}
    )
    if not flag_lines:
        return []
    sentinel_lines = sorted(
        {text.count("\n", 0, m.start()) + 1 for m in DROP_IMPL_RE.finditer(text)}
        | {text.count("\n", 0, m.start()) + 1 for m in UNSAFE_KEYWORD_RE.finditer(text)}
    )
    if not sentinel_lines:
        return []
    out: list[int] = []
    for flag in flag_lines:
        if any(abs(flag - sentinel) <= OWNERSHIP_FLAG_PROXIMITY_LINES for sentinel in sentinel_lines):
            out.append(flag)
    return out


def find_debug_assert_near_unsafe(text: str) -> list[int]:
    """Return line numbers of `debug_assert*!` within ±N lines of an `unsafe` keyword.

    `text` must already have had comments stripped, so that a SAFETY comment
    or a historical mention of `debug_assert` in a doc-comment does not
    fire the rule. Both sentinel sets are computed from the stripped text.
    """
    da_lines = sorted({text.count("\n", 0, m.start()) + 1 for m in DEBUG_ASSERT_RE.finditer(text)})
    if not da_lines:
        return []
    unsafe_lines = sorted({text.count("\n", 0, m.start()) + 1 for m in UNSAFE_KEYWORD_RE.finditer(text)})
    if not unsafe_lines:
        return []
    out: list[int] = []
    for da in da_lines:
        if any(abs(da - u) <= DEBUG_ASSERT_PROXIMITY_LINES for u in unsafe_lines):
            out.append(da)
    return out


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
    raw_unsafe_cell_lines: list[int] = []
    for pattern_name, regex in PATTERNS.items():
        for match in regex.finditer(cleaned):
            line = cleaned.count("\n", 0, match.start()) + 1
            if pattern_name == "UnsafeCell::get":
                raw_unsafe_cell_lines.append(line)
                continue
            findings.append(Finding(rel, pattern_name, line))
    for line in _filter_unsafe_cell_get(cleaned, raw_unsafe_cell_lines):
        findings.append(Finding(rel, "UnsafeCell::get", line))
    for line in find_debug_assert_near_unsafe(cleaned):
        findings.append(Finding(rel, DEBUG_ASSERT_PROXIMITY_PATTERN, line))
    for line in find_ownership_flag_near_drop_or_unsafe(cleaned):
        findings.append(Finding(rel, OWNERSHIP_FLAG_PROXIMITY_PATTERN, line))
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
