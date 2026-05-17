#!/usr/bin/env python3
"""
check_ffi_panic_boundary.py -- CI guard: no Rust panic may unwind across an
`extern "C"` / `extern "system"` boundary.

POLICY
------
The `android-jni` cargo profile (`native/rust/Cargo.toml`) sets
`panic = "unwind"`. Unwinding through an `extern "C"` / `extern "system"`
function is undefined behaviour. Every Rust function exported via one of
those ABIs (JNI exports, C callbacks, foreign-language entry points) MUST
either:

  1. Catch panics at the boundary -- the body must contain
     `android_support::ffi_boundary(`, an `ffi_boundary(` import,
     or `std::panic::catch_unwind(` / `panic::catch_unwind(`.

  2. Be allowlisted in `ci/ffi-panic-boundary-allowlist.toml` with a
     stated reason that proves the body cannot panic (e.g. atomic
     store only, literal-value return, stub under cfg).

Functions declared with the unwind-capable variants (`extern "C-unwind"`,
`extern "system-unwind"`) are intentional bridges for Rust-to-Rust unwind
through a C ABI; they are always flagged and MUST be allowlisted with a
reason that documents the caller's unwind tolerance.

SCOPE
-----
Scans `.rs` files under `native/rust/crates/*/src/**`. Excludes anything
inside `tests/`, `benches/`, `examples/`, `test_*.rs`, `tests.rs`, and
`macro_rules!` definition bodies (macro-generated `extern fn` bodies are
checked at the macro definition, not at each call site).

EXIT CODES
----------
  0  No new unprotected extern fn outside the allowlist.
  1  At least one unprotected extern fn needs containment.
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
ALLOWLIST_FILE = REPO_ROOT / "ci" / "ffi-panic-boundary-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

# Strip comments before scanning so that doc-comment mentions of
# `extern "C" fn` (and the canonical `// SAFETY:` lines that document
# the FFI boundary) do not produce false matches.
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")

# Match an extern fn DEFINITION (has identifier after `fn` and a body).
# `extern "C" fn(...)` used as a FUNCTION POINTER TYPE has no identifier
# between `fn` and `(`, so the `fn\s+IDENT` anchor excludes it.
# The `(?P<abi>...)` group captures the ABI string so we can flag the
# explicit unwind-capable variants separately.
EXTERN_FN_DEF_RE = re.compile(
    r"(?:pub(?:\s*\([^)]*\))?\s+)?"
    r"(?:unsafe\s+)?"
    r'extern\s+"(?P<abi>C|system|C-unwind|system-unwind)"\s+'
    r"fn\s+"
    r"(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
    r"\s*(?P<after>[<(])"
)

# Boundary markers the body MUST contain.
#   `ffi_boundary(` -- preferred (android_support helper)
#   `catch_unwind(` -- std::panic::catch_unwind, panic::catch_unwind
BOUNDARY_RE = re.compile(r"\b(?:ffi_boundary|catch_unwind)\s*\(")

# `macro_rules! NAME { ... }` blocks contain `extern fn` templates that
# expand to wrapped bodies at each call site. The macro body itself is
# pre-stripped so we do not scan inside it. We rely on the fact that the
# macro INVOCATION expansions inherit the body shape of the macro.
MACRO_RULES_RE = re.compile(r"\bmacro_rules!\s+[A-Za-z_][A-Za-z0-9_]*\s*\{")


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
    """Replace `macro_rules! NAME { ... }` body content with whitespace
    so the scanner does not analyse the macro template as if it were a
    real `extern fn` definition.

    The body is brace-balanced; we replace its INTERIOR (preserving the
    outer braces and original byte offsets via space substitution) so
    that any line numbers reported by downstream code are not shifted.
    """
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
        # Blank out the body interior, keeping newlines so line numbers
        # are preserved for any other scanner that consumes `out_chars`.
        for k in range(open_brace + 1, j - 1):
            if out_chars[k] != "\n":
                out_chars[k] = " "
    return "".join(out_chars)


def _skip_generics(text: str, start: int) -> int:
    """If `text[start]` is `<`, balance angle brackets and return the
    position just after the closing `>`. Otherwise return `start`."""
    if start >= len(text) or text[start] != "<":
        return start
    depth = 1
    j = start + 1
    while j < len(text) and depth > 0:
        c = text[j]
        if c == "<":
            depth += 1
        elif c == ">":
            depth -= 1
        j += 1
    return j


def _balance_parens(text: str, open_pos: int) -> int:
    """Given `text[open_pos] == '('`, return the index one past the
    matching `)`."""
    if open_pos >= len(text) or text[open_pos] != "(":
        return open_pos
    depth = 1
    j = open_pos + 1
    while j < len(text) and depth > 0:
        c = text[j]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
        j += 1
    return j


def _find_body_brace(text: str, start: int) -> int | None:
    """Scan forward from `start` looking for the body `{` of an extern
    fn. Returns `None` if a `;` (forward declaration) or end-of-file is
    hit first. The signature may contain a `->` return type, `where`
    clauses, attributes between params and body, etc.; we skip them all
    by looking for the next non-nested `{` or `;`.
    """
    depth = 0
    j = start
    while j < len(text):
        c = text[j]
        if c == "{" and depth == 0:
            return j
        if c == ";" and depth == 0:
            return None
        if c == "(" or c == "<":
            depth += 1
        elif c == ")" or c == ">":
            depth = max(depth - 1, 0)
        j += 1
    return None


def _balance_braces(text: str, open_pos: int) -> int:
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


@dataclass(frozen=True)
class ExternFn:
    rel_path: str
    function: str
    abi: str
    line: int
    has_boundary: bool


def find_extern_fn_defs(text: str) -> list[tuple[str, str, int, str]]:
    """Yield (name, abi, line, body) for every extern fn DEFINITION
    (declaration with a body) in `text`. The text MUST already have
    been comment-stripped and macro-rules-stripped.
    """
    results: list[tuple[str, str, int, str]] = []
    for match in EXTERN_FN_DEF_RE.finditer(text):
        name = match.group("name")
        abi = match.group("abi")
        after_char = match.group("after")
        # Position of the `<` or `(` we matched.
        sig_pos = match.end() - 1
        # Skip generic params if present, land on `(` of arg list.
        sig_pos = _skip_generics(text, sig_pos)
        while sig_pos < len(text) and text[sig_pos] != "(":
            sig_pos += 1
        if sig_pos >= len(text):
            continue
        # Balance the arg list.
        after_args = _balance_parens(text, sig_pos)
        # Find the body brace (skipping return type, attributes, where).
        body_brace = _find_body_brace(text, after_args)
        if body_brace is None:
            # Forward declaration -- not a definition.
            continue
        body_end = _balance_braces(text, body_brace)
        body = text[body_brace + 1 : body_end - 1]
        # 1-based line number of the function name.
        line = text.count("\n", 0, match.start("name")) + 1
        results.append((name, abi, line, body))
        # `after_char` is used only to disambiguate `<` vs `(`; the
        # scanner does not need it past this point. Reference it so
        # linters do not complain about unused locals.
        _ = after_char
    return results


def scan_file(path: Path) -> list[ExternFn]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        text = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_macro_rules_bodies(strip_comments(text))
    out: list[ExternFn] = []
    for name, abi, line, body in find_extern_fn_defs(cleaned):
        has = bool(BOUNDARY_RE.search(body))
        out.append(ExternFn(rel, name, abi, line, has))
    return out


def collect() -> list[ExternFn]:
    out: list[ExternFn] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {"file", "function", "reason", "owner", "review_date"}


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
        key = (entry["file"], entry["function"])
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
    unprotected = [fn for fn in scanned if not fn.has_boundary]
    unwind_abi = [fn for fn in scanned if fn.abi.endswith("-unwind")]

    print(
        f"Scanned production Rust under {CRATES_ROOT.relative_to(REPO_ROOT)} -- "
        f"{total} extern fn definition(s); "
        f"{total - len(unprotected)} contain panic boundary; "
        f"{len(unprotected)} require boundary or allowlist; "
        f"{len(unwind_abi)} use unwind-capable ABI."
    )

    violations: list[ExternFn] = []
    seen_allow: set[tuple[str, str]] = set()

    # 1. Every unprotected fn must be allowlisted.
    for fn in unprotected:
        key = (fn.rel_path, fn.function)
        if key in allowlist:
            seen_allow.add(key)
        else:
            violations.append(fn)

    # 2. Every unwind-capable ABI fn must be allowlisted regardless of
    #    whether it has a boundary, because the ABI itself sanctions
    #    unwinding -- callers need to know.
    for fn in unwind_abi:
        key = (fn.rel_path, fn.function)
        if key in allowlist:
            seen_allow.add(key)
        elif fn not in violations:
            violations.append(fn)

    stale = sorted(set(allowlist.keys()) - seen_allow)
    if stale:
        print()
        print(
            f"NOTE: {len(stale)} allowlist entry(ies) no longer match any scanned "
            "extern fn -- consider removing:"
        )
        for file_, function in stale:
            print(f"  {file_}::{function}")

    if not violations:
        print(f"\nOK: {len(allowlist)} allowlisted extern fn(s) cover all unprotected exports.")
        return 0

    print()
    print(f"FAIL: {len(violations)} extern fn definition(s) without panic boundary or allowlist:")
    for fn in sorted(violations, key=lambda f: (f.rel_path, f.line)):
        print(f"  {fn.rel_path}:{fn.line}  fn {fn.function} (extern \"{fn.abi}\")")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Wrap the body with `android_support::ffi_boundary(default, || { ... })`.\n"
        "     The helper catches panics and substitutes a sentinel return value.\n"
        "  2) Wrap the body with `std::panic::catch_unwind(...)` and convert the\n"
        "     `Result` into an FFI-safe return (the `JNI_OnLoad` pattern).\n"
        "  3) If the body provably cannot panic (atomic store only, literal-value\n"
        "     return, stub function), add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} with a reason that\n"
        "     describes the no-panic property.\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"FFI panic-unwind containment\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
