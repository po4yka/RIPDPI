#!/usr/bin/env python3
"""
check_async_safety.py -- CI guard: every production `async fn` body
that calls a known blocking primitive (`std::fs::*`, `std::net::*`,
`std::thread::sleep`, `ToSocketAddrs::to_socket_addrs`) MUST be
allowlisted with a documented `executor_strategy`.

POLICY
------
See docs/rust-soundness-policy.md, section "Blocking inside async
runtime" (audit issue 39). Calling a blocking primitive on an
`async fn` body that runs on a tokio worker stalls that worker for
the full duration of the syscall. Two consequences:

  1. `tokio::time::timeout` around the blocking work cannot fire
     because the worker is parked in libc and the timer driver does
     not advance.
  2. With `multi_thread(N)`, N concurrent blocking calls consume the
     entire worker pool and the runtime deadlocks.

The fix is one of:

  * Replace with the async-equivalent primitive
    (`tokio::net::lookup_host`, `tokio::fs::*`, `tokio::time::sleep`,
    `tokio::net::TcpStream::connect`).
  * Wrap the call in `tokio::task::spawn_blocking(...)` and `.await`
    the join handle.
  * Move the work to a dedicated long-lived thread that communicates
    with the runtime over a channel.

The default position is: any blocking call inside `async fn` must
appear in `ci/async-safety-allowlist.toml` with a written reason
(e.g. "called from current-thread runtime created per-call;
blocking is acceptable because no other task runs on this runtime").

PATTERNS SCANNED
----------------
This guard implements a coarse two-stage scan. Stage 1 walks the
file and finds every `async fn` body. Stage 2 looks INSIDE those
bodies for any of the blocking-primitive call patterns below.

  * `std::thread::sleep`
  * `std::fs::` (any path)
  * `std::net::TcpStream` / `std::net::UdpSocket` (any method)
  * `to_socket_addrs(` (the `ToSocketAddrs` trait method)
  * `std::process::Command::output` / `.status` / `.spawn`

SCOPE
-----
Scans `.rs` files under `native/rust/crates/*/src/**`. Excludes
`tests/`, `benches/`, `examples/`, `test_*.rs`, `tests.rs`.

EXIT CODES
----------
  0  All hits are allowlisted.
  1  At least one production hit lacks an allowlist entry.
  2  Allowlist file is malformed.

LIMITATIONS
-----------
The scanner does not follow function calls. A blocking primitive
called via a sync helper that is itself called from an async fn
will not be caught here. The `clippy::await_holding_lock` lint and
the `tokio` runtime metrics are the complementary belts that
catch the indirect case.
"""

from __future__ import annotations

import re
import sys
import tomllib
from dataclasses import dataclass
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = REPO_ROOT / "native" / "rust" / "crates"
ALLOWLIST_FILE = REPO_ROOT / "ci" / "async-safety-allowlist.toml"

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]

BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//[^\n]*")
MACRO_RULES_RE = re.compile(r"\bmacro_rules!\s+[A-Za-z_][A-Za-z0-9_]*\s*\{")

# `#[cfg(test)] mod ... { ... }` -- test-gated modules live in `src/`
# but never run in a production runtime. Blocking primitives there are
# legitimate (tests routinely use `std::thread::sleep` for timing
# fixtures and `to_socket_addrs` on already-resolved addresses).
CFG_TEST_MOD_RE = re.compile(
    r"#\[\s*cfg\s*\(\s*test\s*\)\s*\]\s*"
    r"(?:pub(?:\s*\([^)]*\))?\s+)?mod\s+[A-Za-z_][A-Za-z0-9_]*\s*\{"
)

# `async fn` declaration -- captures the position of the opening `{`
# of the body so we can extract the body via brace balancing.
ASYNC_FN_RE = re.compile(
    r"\b(?:pub(?:\s*\([^)]*\))?\s+)?async\s+fn\s+[A-Za-z_][A-Za-z0-9_]*"
    r"(?:\s*<[^>]*>)?\s*\([^)]*\)\s*(?:->\s*[^{]+)?(?:where\s[^\{]+)?\{"
)

# Patterns this guard flags INSIDE an async-fn body.
BLOCKING_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("std::thread::sleep", re.compile(r"\bstd\s*::\s*thread\s*::\s*sleep\b")),
    (
        "std::fs::*",
        re.compile(
            r"\bstd\s*::\s*fs\s*::"
            r"(?:read|read_to_string|write|File\s*::\s*(?:open|create))\b"
        ),
    ),
    (
        "std::net::TcpStream",
        re.compile(r"\bstd\s*::\s*net\s*::\s*TcpStream\s*::\s*(?:connect|connect_timeout)\b"),
    ),
    (
        "std::net::UdpSocket::bind",
        re.compile(r"\bstd\s*::\s*net\s*::\s*UdpSocket\s*::\s*(?:bind|send_to|recv_from)\b"),
    ),
    (
        "to_socket_addrs",
        re.compile(r"\.to_socket_addrs\s*\("),
    ),
    (
        "Command::(output|status|spawn)",
        re.compile(r"\bCommand\s*::\s*new\s*\([^)]*\)[^;]*\.(?:output|status|spawn)\s*\("),
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


def _blank_brace_body(text: str, open_brace_pos: int) -> tuple[int, list[int]]:
    """Return the position after the matching `}` plus the list of
    character indices the caller should blank out (preserving newlines)."""
    if open_brace_pos >= len(text) or text[open_brace_pos] != "{":
        return open_brace_pos, []
    depth = 1
    j = open_brace_pos + 1
    while j < len(text) and depth > 0:
        c = text[j]
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
        j += 1
    return j, list(range(open_brace_pos + 1, j - 1))


def strip_macro_rules_bodies(text: str) -> str:
    out_chars = list(text)
    for match in MACRO_RULES_RE.finditer(text):
        _end, indices = _blank_brace_body(text, match.end() - 1)
        for k in indices:
            if out_chars[k] != "\n":
                out_chars[k] = " "
    return "".join(out_chars)


def strip_cfg_test_modules(text: str) -> str:
    """Blank out the contents of every `#[cfg(test)] mod ... { ... }`
    block (preserving newlines so line numbers stay accurate).

    Production blocking-in-async discipline does not extend to test
    fixtures; tests routinely use `std::thread::sleep` for timing
    setup and `to_socket_addrs` on already-resolved addresses."""
    out_chars = list(text)
    for match in CFG_TEST_MOD_RE.finditer(text):
        _end, indices = _blank_brace_body(text, match.end() - 1)
        for k in indices:
            if out_chars[k] != "\n":
                out_chars[k] = " "
    return "".join(out_chars)


def balance_braces(text: str, open_pos: int) -> int:
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


@dataclass(frozen=True)
class AsyncHit:
    rel_path: str
    pattern_id: str
    line: int
    excerpt: str


def extract_async_bodies(text: str) -> list[tuple[int, int]]:
    """Return `[(start, end), ...]` byte offsets of every async-fn body
    in `text`, with `start` at the position AFTER the opening `{`
    and `end` at the position OF the matching `}`."""
    out: list[tuple[int, int]] = []
    for match in ASYNC_FN_RE.finditer(text):
        open_brace = match.end() - 1
        end = balance_braces(text, open_brace)
        out.append((open_brace + 1, end - 1))
    return out


def scan_file(path: Path) -> list[AsyncHit]:
    rel = str(path.relative_to(REPO_ROOT))
    try:
        original = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return []
    cleaned = strip_cfg_test_modules(strip_macro_rules_bodies(strip_comments(original)))
    lines = cleaned.splitlines()
    out: list[AsyncHit] = []
    bodies = extract_async_bodies(cleaned)
    for start, end in bodies:
        body = cleaned[start:end]
        body_line_offset = cleaned.count("\n", 0, start)
        for pattern_id, regex in BLOCKING_PATTERNS:
            for m in regex.finditer(body):
                line_no = body_line_offset + body.count("\n", 0, m.start()) + 1
                excerpt = lines[line_no - 1].strip() if line_no - 1 < len(lines) else ""
                out.append(
                    AsyncHit(
                        rel_path=rel,
                        pattern_id=pattern_id,
                        line=line_no,
                        excerpt=excerpt[:160],
                    )
                )
    return out


def collect() -> list[AsyncHit]:
    out: list[AsyncHit] = []
    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        out.extend(scan_file(rs_file))
    return out


REQUIRED_ALLOWLIST_FIELDS = {
    "file",
    "pattern",
    "line",
    "executor_strategy",
    "reason",
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
        f"{len(hits)} blocking-in-async hit(s); "
        f"{len(allowlist)} allowlisted."
    )

    violations: list[AsyncHit] = []
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
        print("\nOK: no unallowlisted blocking-in-async call sites.")
        return 0

    print()
    print(
        f"FAIL: {len(violations)} production blocking-in-async hit(s) "
        "lack an allowlist entry:"
    )
    for hit in sorted(violations, key=lambda h: (h.rel_path, h.line)):
        print(f"  {hit.rel_path}:{hit.line}  [{hit.pattern_id}]")
        if hit.excerpt:
            print(f"    {hit.excerpt}")
    print(
        "\nTo fix, in order of preference:\n"
        "  1) Replace with the async equivalent\n"
        "     (`tokio::net::lookup_host`, `tokio::fs::*`,\n"
        "     `tokio::time::sleep`, `tokio::net::TcpStream::connect`).\n"
        "  2) Wrap in `tokio::task::spawn_blocking(move || { ... })`\n"
        "     and `.await` the join handle.\n"
        f"  3) If neither applies, add an entry to\n"
        f"     {ALLOWLIST_FILE.relative_to(REPO_ROOT)} naming the\n"
        "     `executor_strategy` that makes the blocking safe\n"
        "     (e.g. dedicated current-thread runtime, single-task\n"
        "     bootstrap before runtime steady-state).\n"
        "\nPolicy: docs/rust-soundness-policy.md  -- \"Blocking inside\n"
        "async runtime\""
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
