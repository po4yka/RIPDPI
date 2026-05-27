#!/usr/bin/env python3
"""Verify SPEC_VERSION.md presence and required fields across protocol crates.

Format-only by default (offline-safe). The full reachability check against
upstream remotes is planned for the weekly workflow; see
`.github/workflows/upstream-spec-watch.yml`.

Usage:
    scripts/ci/verify_spec_versions.py [--format-only]
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

CRATES = [
    "ripdpi-vless",
    "ripdpi-xhttp",
    "ripdpi-hysteria2",
    "ripdpi-tuic",
    "ripdpi-shadowtls",
    "ripdpi-anytls",
    "ripdpi-naiveproxy",
    "ripdpi-ws-tunnel",
]
REPO_ROOT = Path(__file__).resolve().parents[2]
CRATES_ROOT = REPO_ROOT / "native" / "rust" / "crates"
REQUIRED_FIELDS = [
    "Upstream repo:",
    "Upstream tag:",
    "Upstream commit:",
    "Last reviewed:",
]


def check_crate(crate: str) -> list[str]:
    failures: list[str] = []
    path = CRATES_ROOT / crate / "SPEC_VERSION.md"
    if not path.exists():
        failures.append(f"{crate}: missing SPEC_VERSION.md at {path.relative_to(REPO_ROOT)}")
        return failures
    content = path.read_text(encoding="utf-8")
    for field in REQUIRED_FIELDS:
        if field not in content:
            failures.append(f"{crate}: missing required field '{field}'")
    return failures


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--format-only",
        action="store_true",
        help="(default) check only presence and required fields, no network access",
    )
    parser.parse_args(argv[1:])

    all_failures: list[str] = []
    for crate in CRATES:
        all_failures.extend(check_crate(crate))

    if all_failures:
        print("SPEC_VERSION verification FAILED:", file=sys.stderr)
        for line in all_failures:
            print(f"  - {line}", file=sys.stderr)
        return 1

    print(f"OK: all {len(CRATES)} protocol crates have valid SPEC_VERSION.md")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
