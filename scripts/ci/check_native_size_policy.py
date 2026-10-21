#!/usr/bin/env python3
"""Validate the label-approved native growth policy without changing measurements."""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess


ROOT = Path(__file__).resolve().parents[2]
POLICY_PATH = "scripts/ci/native-size-baseline.json"
APPROVAL_LABEL = "approved-native-size-policy"
LIMITS = ("maxPerLibraryGrowthBytes", "maxTotalGrowthPercent", "maxTotalGrowthBytes")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def read_policy(revision: str) -> dict:
    mode = subprocess.check_output(
        ["git", "ls-tree", "--format=%(objectmode)", revision, "--", POLICY_PATH], cwd=ROOT, text=True,
    ).strip()
    if mode != "100644":
        raise ValueError("native size policy must remain a regular non-executable file")
    data = subprocess.check_output(["git", "show", f"{revision}:{POLICY_PATH}"], cwd=ROOT, text=True)
    return json.loads(data, object_pairs_hook=unique_object)


def approved_base(event: dict) -> str:
    pull = event.get("pull_request", {})
    if not any(label.get("name") == APPROVAL_LABEL for label in pull.get("labels", [])):
        raise ValueError(f"native size policy changes require the maintainer-applied {APPROVAL_LABEL} label")
    base = pull.get("base", {}).get("sha", "")
    if not isinstance(base, str) or re.fullmatch(r"[0-9a-f]{40}", base) is None:
        raise ValueError("missing exact PR base SHA")
    return base


def validate_delta(base: dict, head: dict) -> None:
    if not isinstance(base, dict) or not isinstance(head, dict) or set(base) != set(head):
        raise ValueError("native size policy fields must remain unchanged")
    fixed_base = {key: value for key, value in base.items() if key not in (*LIMITS, "_comment")}
    fixed_head = {key: value for key, value in head.items() if key not in (*LIMITS, "_comment")}
    if json.dumps(fixed_base, sort_keys=True, allow_nan=False) != json.dumps(fixed_head, sort_keys=True, allow_nan=False):
        raise ValueError("native size measurements and other policy fields must remain unchanged")
    increased = False
    for key in LIMITS:
        value = head.get(key)
        valid_type = type(value) in (int, float) if key == "maxTotalGrowthPercent" else type(value) is int
        if not valid_type or not math.isfinite(value) or value <= 0 or value < base[key]:
            raise ValueError(f"{key} must be a finite positive limit, no smaller than its PR base value")
        increased |= value > base[key]
    if not increased:
        raise ValueError("approved policy changes must increase a growth limit")
    previous, comment = base.get("_comment"), head.get("_comment")
    if not isinstance(previous, str) or not isinstance(comment, str) or not comment.startswith(previous) or not comment[len(previous):].strip():
        raise ValueError("_comment must append approval provenance and retain its exact previous text")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--event-path", type=Path, default=os.environ.get("GITHUB_EVENT_PATH"))
    args = parser.parse_args()
    if args.event_path is None:
        parser.error("a GitHub pull-request event is required")
    try:
        event = json.loads(args.event_path.read_text())
        base = approved_base(event)
        validate_delta(read_policy(base), read_policy("HEAD"))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print("Approved native growth limits; all measurements and previous provenance are unchanged")


if __name__ == "__main__":
    main()
