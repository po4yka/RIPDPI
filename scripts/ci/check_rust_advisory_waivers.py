#!/usr/bin/env python3
"""Validate ownership and expiry metadata for every cargo-deny advisory waiver."""

from __future__ import annotations

import argparse
import datetime as dt
import sys
import tomllib
from pathlib import Path
from typing import Any


REQUIRED_FIELDS = ("id", "owner", "reason", "expires", "tracking")


def _load_toml(path: Path) -> dict[str, Any]:
    with path.open("rb") as handle:
        return tomllib.load(handle)


def validate(deny_path: Path, waivers_path: Path, repo_root: Path, today: dt.date) -> list[str]:
    errors: list[str] = []
    deny_entries = _load_toml(deny_path).get("advisories", {}).get("ignore", [])
    waiver_entries = _load_toml(waivers_path).get("waivers", [])

    ignored: dict[str, dict[str, Any]] = {}
    for index, entry in enumerate(deny_entries):
        if not isinstance(entry, dict):
            errors.append(f"deny.toml advisories.ignore[{index}] must be a table with id and reason")
            continue
        advisory_id = entry.get("id")
        reason = entry.get("reason")
        if not isinstance(advisory_id, str) or not advisory_id:
            errors.append(f"deny.toml advisories.ignore[{index}] has no advisory id")
            continue
        if advisory_id in ignored:
            errors.append(f"duplicate deny.toml advisory ignore: {advisory_id}")
        if not isinstance(reason, str) or not reason.strip():
            errors.append(f"deny.toml advisory {advisory_id} has no reason")
        ignored[advisory_id] = entry

    waivers: dict[str, dict[str, Any]] = {}
    for index, entry in enumerate(waiver_entries):
        if not isinstance(entry, dict):
            errors.append(f"advisory-waivers.toml waivers[{index}] must be a table")
            continue
        missing = [field for field in REQUIRED_FIELDS if not isinstance(entry.get(field), str) or not entry[field].strip()]
        if missing:
            errors.append(f"advisory-waivers.toml waivers[{index}] is missing: {', '.join(missing)}")
            continue
        advisory_id = entry["id"]
        if advisory_id in waivers:
            errors.append(f"duplicate advisory waiver metadata: {advisory_id}")
            continue
        waivers[advisory_id] = entry

        try:
            expiry = dt.date.fromisoformat(entry["expires"])
        except ValueError:
            errors.append(f"advisory waiver {advisory_id} has invalid ISO expiry: {entry['expires']}")
        else:
            if expiry <= today:
                errors.append(f"advisory waiver {advisory_id} expired on {expiry.isoformat()}")

        tracking = Path(entry["tracking"])
        if tracking.is_absolute() or ".." in tracking.parts:
            errors.append(f"advisory waiver {advisory_id} tracking path must stay inside the repository")
        elif not (repo_root / tracking).is_file():
            errors.append(f"advisory waiver {advisory_id} tracking task does not exist: {tracking}")

    for advisory_id in sorted(ignored.keys() - waivers.keys()):
        errors.append(f"advisory ignore {advisory_id} has no owner/expiry metadata")
    for advisory_id in sorted(waivers.keys() - ignored.keys()):
        errors.append(f"advisory waiver metadata {advisory_id} has no matching deny.toml ignore")

    return errors


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--deny", type=Path, default=repo_root / "native/rust/deny.toml")
    parser.add_argument("--waivers", type=Path, default=repo_root / "native/rust/advisory-waivers.toml")
    parser.add_argument("--repo-root", type=Path, default=repo_root)
    parser.add_argument("--today", type=dt.date.fromisoformat, default=dt.date.today())
    args = parser.parse_args()

    errors = validate(args.deny, args.waivers, args.repo_root, args.today)
    if errors:
        for error in errors:
            print(f"error: {error}", file=sys.stderr)
        return 1
    print("Rust advisory waivers: all ignores have current owner/reason/expiry metadata")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
