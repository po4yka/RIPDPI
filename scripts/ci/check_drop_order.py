#!/usr/bin/env python3
"""
check_drop_order.py -- CI guard: every struct with `impl Drop` and 2+
resource-bearing fields must document its field/drop ordering rationale
or be allowlisted.

POLICY
------
Rust runs the user-defined `Drop::drop` body FIRST, then drops each
field in declaration order (top-to-bottom). When a struct owns more
than one resource at a time (a worker thread plus its shutdown
channel; a callback registration plus the context it reads through;
a lock plus the artifact whose lifecycle is bound to it; multiple
raw pointers), the field order, the Drop body's operation order, OR
both are part of the type's safety contract -- not an incidental
detail.

Issue #31 (incorrect field/drop order) is the canonical failure
mode: a refactor reshuffles fields, the Drop body keeps working
under unit tests, but a subtle UAF / lock-after-drop / runtime-
self-deadlock surfaces in production under load.

This guard enforces a documentation-and-allowlist discipline:

  Every struct in production Rust crates with `impl Drop` AND 2+
  fields whose type matches a resource pattern (see RESOURCE_FIELD_
  PATTERNS below) MUST satisfy ONE of:

    1. The file contains a `Drop order:` marker comment (case-
       insensitive) anywhere -- typically in a struct-level or
       impl-level doc-comment that names which field drops first
       and why.
    2. The struct/type is allowlisted in
       `ci/drop-order-allowlist.toml` with a `reason` field that
       names the discipline pattern in use (Option::take + join,
       abort-and-detach, sole-owner mmap, etc.).

SCOPE
-----
Scans `.rs` files under `native/rust/crates/*/src/**`. Excludes
anything inside `tests/`, `benches/`, `examples/`, `test_*.rs`,
and `tests.rs`. The intent is to police PRODUCTION teardown
discipline -- test fixtures may choose simpler patterns.

EXIT CODES
----------
  0  All multi-resource Drop impls documented or allowlisted.
  1  At least one multi-resource Drop impl needs documentation
     or allowlist.
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
ALLOWLIST_FILE = REPO_ROOT / "ci" / "drop-order-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

# Strip comments before SCAN STRUCTURE; the marker-comment check
# runs against the ORIGINAL text (since the marker IS a comment).
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

# `macro_rules! NAME { ... }` -- macro bodies can contain `struct`
# and `impl Drop` templates that must NOT be scanned as real defs.
MACRO_RULES_RE = re.compile(r"\bmacro_rules!\s+[A-Za-z_][A-Za-z0-9_]*\s*\{")

# `impl<...> Drop for TypeName<...>` -- captures the base type name.
IMPL_DROP_RE = re.compile(
    r"\bimpl(?:\s*<[^>]*>)?\s+Drop\s+for\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
    r"(?:\s*<[^>]*>)?\s*(?:where\s[^\{;]*)?\{"
)

# `struct TypeName<...> { ... }` -- captures the base type name and
# delegates body extraction to a brace-balancer.
STRUCT_DEF_RE = re.compile(
    r"(?:^|\n)\s*(?:#\[[^\]]*\]\s*)*(?:pub(?:\s*\([^)]*\))?\s+)?"
    r"struct\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
    r"(?:\s*<[^>]*>)?\s*(?:where\s[^\{;]*)?\{"
)

# A field declaration inside a struct body. Captures the type
# expression up to the next top-level comma. The full type may
# contain `<...>` and nested commas; FIELD_SPLIT below balances
# angle brackets to split.
FIELD_NAME_RE = re.compile(
    r"^\s*(?:#\[[^\]]*\]\s*)*"
    r"(?:pub(?:\s*\([^)]*\))?\s+)?"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*:"
)

# Marker comment that satisfies the documentation requirement.
# Case-insensitive; allowed in line comments, block comments, or
# doc comments. Examples that count:
#   // Drop order: tx before thread; see Drop::drop.
#   /// Drop order: state-mutation in body precedes implicit
#   /// permit release.
#   /* Drop order: ... */
DROP_ORDER_MARKER_RE = re.compile(r"drop\s+order\s*:", re.IGNORECASE)

# Field type patterns that indicate the field owns a teardown-
# sensitive resource. A struct with 2+ matching fields plus an
# `impl Drop` is in scope for the discipline check.
#
# Each pattern is a substring (or regex) tested against the field's
# type expression. The patterns intentionally over-match (e.g.
# any `JoinHandle<` regardless of crate origin) -- false positives
# are cheaper than false negatives for this guard.
RESOURCE_FIELD_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("JoinHandle", re.compile(r"\bJoinHandle\b")),
    ("Runtime", re.compile(r"\b(?:tokio::runtime::)?Runtime\b")),
    ("oneshot::Sender", re.compile(r"\boneshot::Sender\b")),
    ("mpsc::Sender", re.compile(r"\bmpsc::(?:Unbounded)?Sender\b")),
    ("flume::Sender", re.compile(r"\bflume::Sender\b")),
    ("broadcast::Sender", re.compile(r"\bbroadcast::Sender\b")),
    ("watch::Sender", re.compile(r"\bwatch::Sender\b")),
    ("CancellationToken", re.compile(r"\bCancellationToken\b")),
    ("raw pointer (*mut)", re.compile(r"\*\s*mut\s+[A-Za-z_]")),
    ("raw pointer (*const)", re.compile(r"\*\s*const\s+[A-Za-z_]")),
    ("NonNull", re.compile(r"\bNonNull\s*<")),
    ("Box<dyn ...>", re.compile(r"\bBox\s*<\s*dyn\b")),
    ("OwnedFd", re.compile(r"\bOwnedFd\b")),
    ("RawFd", re.compile(r"\bRawFd\b")),
    ("Mmap / MmapMut", re.compile(r"\bMmap(?:Mut)?\b")),
    ("Flock", re.compile(r"\bFlock\s*<")),
    ("MutexGuard", re.compile(r"\bMutexGuard\b")),
    ("RwLockGuard", re.compile(r"\b(?:RwLockReadGuard|RwLockWriteGuard|RwLockGuard)\b")),
    ("File handle", re.compile(r"\bFile\b(?!System)")),
    ("Semaphore permit", re.compile(r"\bOwnedSemaphorePermit\b|\bSemaphorePermit\b")),
    ("AbortHandle", re.compile(r"\bAbortHandle\b")),
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


def _balance_braces(text: str, open_pos: int) -> int:
    """`text[open_pos] == '{'`. Returns the index one past the matching `}`."""
    if open_pos >= len(text) or text[open_pos] != "{":
        return open_pos
    depth = 1
    j = open_pos + 1
    while j < len(text) and depth > 0:
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        j += 1
    return j


def _split_top_level_commas(text: str) -> list[str]:
    """Split `text` on commas that are NOT inside `<...>`, `(...)`,
    `[...]`, or `{...}` -- i.e. top-level field separators."""
    pieces: list[str] = []
    depth_angle = 0
    depth_paren = 0
    depth_bracket = 0
    depth_brace = 0
    start = 0
    for i, c in enumerate(text):
        if c == "<":
            depth_angle += 1
        elif c == ">":
            depth_angle = max(depth_angle - 1, 0)
        elif c == "(":
            depth_paren += 1
        elif c == ")":
            depth_paren = max(depth_paren - 1, 0)
        elif c == "[":
            depth_bracket += 1
        elif c == "]":
            depth_bracket = max(depth_bracket - 1, 0)
        elif c == "{":
            depth_brace += 1
        elif c == "}":
            depth_brace = max(depth_brace - 1, 0)
        elif c == "," and depth_angle == 0 and depth_paren == 0 and depth_bracket == 0 and depth_brace == 0:
            pieces.append(text[start:i])
            start = i + 1
    if start < len(text):
        pieces.append(text[start:])
    return [p.strip() for p in pieces if p.strip()]


def parse_fields(body: str) -> list[tuple[str, str]]:
    """Return `[(field_name, field_type), ...]` for the fields of a
    struct body. `body` is the text BETWEEN the outer braces (no
    leading `{` / trailing `}`). Field-level attributes (`#[serde]`,
    etc.) and visibility modifiers are tolerated.
    """
    fields: list[tuple[str, str]] = []
    for piece in _split_top_level_commas(body):
        m = FIELD_NAME_RE.match(piece)
        if not m:
            continue
        name = m.group("name")
        type_expr = piece[m.end() :].strip()
        if type_expr:
            fields.append((name, type_expr))
    return fields


@dataclass(frozen=True)
class DropCandidate:
    rel_path: str
    type_name: str
    line: int
    resource_fields: tuple[tuple[str, str, str], ...]  # (field_name, type, matched_pattern_label)
    has_marker_comment: bool


def find_struct_body(text: str, type_name: str) -> tuple[int, str] | None:
    """Return `(line, body)` for `struct {type_name} { ... }` in
    `text`, or None if no such struct is in this file. `text` is
    expected to already have comments and macro_rules bodies stripped.
    """
    for match in STRUCT_DEF_RE.finditer(text):
        if match.group("name") != type_name:
            continue
        open_brace = match.end() - 1
        close = _balance_braces(text, open_brace)
        body = text[open_brace + 1 : close - 1]
        line = text.count("\n", 0, match.start("name")) + 1
        return line, body
    return None


def find_impl_drop_targets(text: str) -> list[tuple[str, int]]:
    """Return `[(type_name, line), ...]` for every `impl Drop for T`
    in `text`. Duplicates allowed (cfg-gated impls share a type
    name); caller deduplicates by type name as needed.
    """
    out: list[tuple[str, int]] = []
    for match in IMPL_DROP_RE.finditer(text):
        name = match.group("name")
        line = text.count("\n", 0, match.start("name")) + 1
        out.append((name, line))
    return out


def classify_field_resources(fields: list[tuple[str, str]]) -> list[tuple[str, str, str]]:
    """For each `(name, type_expr)`, return the first matching
    `(name, type_expr, pattern_label)` if any resource pattern hits.
    Fields with no match are dropped.
    """
    flagged: list[tuple[str, str, str]] = []
    for name, type_expr in fields:
        for label, pat in RESOURCE_FIELD_PATTERNS:
            if pat.search(type_expr):
                flagged.append((name, type_expr, label))
                break
    return flagged


def scan_file(path: Path) -> list[DropCandidate]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        original = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    # Comment-stripped + macro-stripped text for STRUCTURE.
    cleaned = strip_macro_rules_bodies(strip_comments(original))
    # The marker check runs against the ORIGINAL text -- the marker
    # is itself a comment so the cleaned text has it removed.
    has_marker = bool(DROP_ORDER_MARKER_RE.search(original))

    drop_targets = find_impl_drop_targets(cleaned)
    seen: set[str] = set()
    out: list[DropCandidate] = []
    for type_name, _impl_line in drop_targets:
        if type_name in seen:
            continue
        seen.add(type_name)
        struct = find_struct_body(cleaned, type_name)
        if struct is None:
            # Drop impl without same-file struct definition -- skip;
            # we only police same-file pairs in v1.
            continue
        struct_line, body = struct
        fields = parse_fields(body)
        flagged = classify_field_resources(fields)
        if len(flagged) < 2:
            continue
        out.append(
            DropCandidate(
                rel_path=rel,
                type_name=type_name,
                line=struct_line,
                resource_fields=tuple(flagged),
                has_marker_comment=has_marker,
            )
        )
    return out


def collect() -> list[DropCandidate]:
    out: list[DropCandidate] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {"file", "type_name", "reason", "owner", "review_date"}


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
        key = (entry["file"], entry["type_name"])
        if key in out:
            print(
                f"ERROR: duplicate allowlist entry for {key}",
                file=sys.stderr,
            )
            sys.exit(2)
        out[key] = entry
    return out


def main() -> int:
    allowlist = load_allowlist()
    scanned = collect()
    total = len(scanned)
    documented = [c for c in scanned if c.has_marker_comment]
    undocumented = [c for c in scanned if not c.has_marker_comment]

    print(
        f"Scanned production Rust under {CRATES_ROOT.relative_to(REPO_ROOT)} -- "
        f"{total} struct(s) with `impl Drop` AND 2+ resource fields; "
        f"{len(documented)} carry a `Drop order:` marker comment; "
        f"{len(undocumented)} need a marker or allowlist."
    )

    violations: list[DropCandidate] = []
    seen_allow: set[tuple[str, str]] = set()
    for candidate in undocumented:
        key = (candidate.rel_path, candidate.type_name)
        if key in allowlist:
            seen_allow.add(key)
        else:
            violations.append(candidate)

    stale = sorted(set(allowlist.keys()) - seen_allow)
    if stale:
        print()
        print(
            f"NOTE: {len(stale)} allowlist entry(ies) no longer match any "
            "scanned struct -- consider removing:"
        )
        for file_, type_name in stale:
            print(f"  {file_}::{type_name}")

    if not violations:
        print(f"\nOK: {len(allowlist)} allowlisted struct(s) plus {len(documented)} marker-documented.")
        return 0

    print()
    print(
        f"FAIL: {len(violations)} struct(s) with multi-resource `impl Drop` "
        "lack a `Drop order:` marker comment and are not allowlisted:"
    )
    for candidate in sorted(violations, key=lambda c: (c.rel_path, c.line)):
        print(f"  {candidate.rel_path}:{candidate.line}  struct {candidate.type_name}")
        for field_name, _type_expr, label in candidate.resource_fields:
            print(f"    - {field_name}: {label}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Add a `// Drop order: <field-A> drops/teardown-before <field-B>; <reason>` comment\n"
        "     near the struct or `impl Drop` block. The marker is case-insensitive and may\n"
        "     live in a line, block, or doc comment.\n"
        "  2) If the struct's drop order is genuinely irrelevant (e.g. every resource field\n"
        "     is an `Arc<T>` with no cross-Drop interaction), add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with a reason that describes the\n"
        "     no-ordering property.\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"Field declaration order in Drop impls\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
