#!/usr/bin/env python3
"""
check_packed_structs.py -- CI guard: every `#[repr(packed)]` (or
`packed(N)`) struct in production Rust crates MUST be allowlisted
with a documented rationale.

POLICY
------
Fields of a `#[repr(packed)]` struct may be unaligned. Creating any
ordinary Rust reference to such a field is undefined behavior, even
on CPUs that support unaligned access -- rustc emits
`unaligned_references` (deny by default since 1.71) and the workspace
elevates that to `forbid` in `[workspace.lints.rust]`. The Clippy
lint covers REFERENCES; this guard covers the upstream decision to
USE `repr(packed)` in the first place.

The audit position (docs/rust-soundness-policy.md "repr(packed)
discipline") is:

  1. Wire formats are decoded with explicit byte parsing (`u32::
     from_le_bytes(buf[0..4].try_into()?)`), NOT by mapping bytes
     onto a packed struct.
  2. The only acceptable use of `repr(packed)` is when an external
     ABI demands the exact byte layout (e.g. a kernel UAPI struct
     with a 1-byte version field followed by a 4-byte length) AND
     field access is performed via `addr_of!` + `read_unaligned`.
  3. Such a struct MUST appear in `ci/packed-allowlist.toml` with
     the ABI it satisfies, the access discipline it uses, and the
     owner team.

SCOPE
-----
Scans `.rs` files under `native/rust/crates/*/src/**`. Excludes
`tests/`, `benches/`, `examples/`, `test_*.rs`, `tests.rs`. Test
fixtures may use `repr(packed)` for parser-input construction;
production code may not.

EXIT CODES
----------
  0  All `repr(packed)` types are allowlisted.
  1  At least one production `repr(packed)` type lacks an allowlist
     entry.
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
ALLOWLIST_FILE = REPO_ROOT / "ci" / "packed-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

# `#[repr(...)]` attribute -- captures the argument list so we can
# scan for `packed` (with or without a `(N)` alignment override).
# Tolerates a single nesting level for `packed(N)` and `align(N)`;
# the alignment integer is the only nested form rustc accepts here.
REPR_ATTR_RE = re.compile(
    r"#\[\s*repr\s*\(\s*(?P<args>[^()]*(?:\([^()]*\)[^()]*)*)\s*\)\s*\]"
)

# Captures the name of a struct definition. The `type_name` group
# captures the identifier; the rest of the regex tolerates lifetime/
# type parameters and the where-clause.
STRUCT_DEF_RE = re.compile(
    r"\b(?:pub(?:\s*\([^)]*\))?\s+)?struct\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)


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


def repr_args_contain_packed(args: str) -> bool:
    """Return True iff the repr argument list mentions `packed`
    (`packed`, `packed(2)`, etc.). Whitespace tolerated."""
    for token in re.split(r"[\s,]+", args.strip()):
        if token == "packed" or token.startswith("packed("):
            return True
    return False


@dataclass(frozen=True)
class PackedHit:
    rel_path: str
    type_name: str
    line: int
    repr_args: str


def scan_file(path: Path) -> list[PackedHit]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    out: list[PackedHit] = []
    for repr_match in REPR_ATTR_RE.finditer(text):
        args = repr_match.group("args")
        if not repr_args_contain_packed(args):
            continue
        # Find the next `struct NAME` after this attribute.
        struct_match = STRUCT_DEF_RE.search(text, repr_match.end())
        if struct_match is None:
            continue
        line = text.count("\n", 0, struct_match.start("name")) + 1
        out.append(
            PackedHit(
                rel_path=rel,
                type_name=struct_match.group("name"),
                line=line,
                repr_args=args.strip(),
            )
        )
    return out


def collect() -> list[PackedHit]:
    out: list[PackedHit] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {
    "file",
    "type_name",
    "abi_source",
    "access_discipline",
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
        key = (entry["file"], entry["type_name"])
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
        f"{len(hits)} struct(s) with `#[repr(packed)]`; "
        f"{len(allowlist)} allowlisted."
    )

    violations: list[PackedHit] = []
    seen_allow: set[tuple[str, str]] = set()
    for hit in hits:
        key = (hit.rel_path, hit.type_name)
        if key in allowlist:
            seen_allow.add(key)
        else:
            violations.append(hit)

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
        print("\nOK: no unallowlisted `#[repr(packed)]` types.")
        return 0

    print()
    print(
        f"FAIL: {len(violations)} production `#[repr(packed)]` struct(s) "
        "lack an allowlist entry:"
    )
    for hit in sorted(violations, key=lambda h: (h.rel_path, h.line)):
        print(f"  {hit.rel_path}:{hit.line}  struct {hit.type_name}  // repr({hit.repr_args})")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Replace the packed struct with explicit byte parsing\n"
        "     (`u32::from_le_bytes(buf[0..4].try_into()?)`).\n"
        "  2) If an external ABI demands the exact byte layout, add an entry\n"
        f"     to {ALLOWLIST_FILE.relative_to(REPO_ROOT)} naming the ABI source\n"
        "     and the access discipline (`addr_of!` + `read_unaligned` is the\n"
        "     only safe choice).\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"repr(packed) discipline\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
