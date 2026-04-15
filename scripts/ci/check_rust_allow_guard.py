#!/usr/bin/env python3
"""
check_rust_allow_guard.py -- CI guard against new #[allow(...)] annotations in production Rust.

BASELINE POLICY
---------------
The companion file rust-allow-baseline.json records the EXISTING allow-annotation debt
snapshotted from main at the time this guard was introduced. It exists ONLY to grandfather
legacy suppressions; it must NEVER be extended. The correct remediation for any violation
flagged by this script is to remove (or justify and fix) the annotation in source, not to
bump the baseline number.

SCOPE
-----
Scans all .rs files under native/rust/crates/*/src/**
Excludes:
  - Any file inside a tests/, benches/, or examples/ directory segment.
  - Any file named tests.rs or matching test_*.rs.

EXIT CODES
----------
  0  All files are at or below their baseline allow count.
  1  One or more files exceed their baseline (or a new file has any allows).
"""

import json
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = REPO_ROOT / "native" / "rust" / "crates"
BASELINE_FILE = Path(__file__).resolve().parent / "rust-allow-baseline.json"

ALLOW_PATTERN = re.compile(r"#!?\[allow\(")

EXCLUDE_DIRS = {"tests", "benches", "examples"}
EXCLUDE_FILE_RE = [
    re.compile(r"(^|/)tests\.rs$"),
    re.compile(r"(^|/)test_[^/]+\.rs$"),
]


def is_excluded(path: Path) -> bool:
    rel = str(path.relative_to(REPO_ROOT))
    # Check directory segments after 'src/'
    parts = path.parts
    try:
        src_idx = parts.index("src")
    except ValueError:
        return True
    sub_dirs = parts[src_idx + 1 : -1]  # directory segments only, not filename
    if any(d in EXCLUDE_DIRS for d in sub_dirs):
        return True
    # Check filename patterns
    if any(pat.search(rel) for pat in EXCLUDE_FILE_RE):
        return True
    return False


def count_allows(path: Path) -> int:
    try:
        content = path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return 0
    return len(ALLOW_PATTERN.findall(content))


def main() -> int:
    if not BASELINE_FILE.exists():
        print(f"ERROR: baseline file not found: {BASELINE_FILE}", file=sys.stderr)
        return 1

    with BASELINE_FILE.open(encoding="utf-8") as fh:
        baseline: dict[str, int] = json.load(fh)

    violations: list[tuple[str, int, int]] = []  # (rel_path, actual, allowed)
    scanned = 0

    for rs_file in sorted(CRATES_ROOT.glob("*/src/**/*.rs")):
        if is_excluded(rs_file):
            continue
        scanned += 1
        rel = str(rs_file.relative_to(REPO_ROOT))
        actual = count_allows(rs_file)
        allowed = baseline.get(rel, 0)
        if actual > allowed:
            violations.append((rel, actual, allowed))

    print(f"Scanned {scanned} production Rust file(s).")

    if not violations:
        total_baseline = sum(baseline.values())
        print(f"OK: all files within baseline (total baseline allows: {total_baseline}).")
        return 0

    print(f"\nFAIL: {len(violation := violations)} file(s) exceed their allow baseline:\n")
    for rel, actual, allowed in violations:
        if allowed == 0:
            label = "new file -- no allows permitted"
        else:
            label = f"baseline: {allowed}"
        print(f"  {rel}")
        print(f"    found: {actual}  {label}  excess: {actual - allowed}")
    print(
        "\nTo fix: remove the excess #[allow(...)] annotations or resolve the underlying "
        "lint warnings.\nDo NOT increase numbers in rust-allow-baseline.json."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
