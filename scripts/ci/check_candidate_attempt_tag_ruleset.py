#!/usr/bin/env python3
"""Require immutable protection for durable release-candidate attempt tags."""

from __future__ import annotations

import argparse
import json
import subprocess
from typing import Any


RULESET_NAME = "immutable-release-candidate-attempt-tags"
REF_PATTERN = "refs/tags/release-candidates/v*/run-*"


def validate_rulesets(payload: Any) -> None:
    if not isinstance(payload, list):
        raise ValueError("repository rulesets response must be an array")
    matches = [item for item in payload if isinstance(item, dict) and item.get("name") == RULESET_NAME]
    if len(matches) != 1:
        raise ValueError(f"required ruleset is missing: {RULESET_NAME}")
    ruleset = matches[0]
    if ruleset.get("enforcement") != "active":
        raise ValueError("candidate-attempt tag ruleset must be active")
    if ruleset.get("bypass_actors"):
        raise ValueError("candidate-attempt tag ruleset must not have bypass actors")
    includes = ruleset.get("conditions", {}).get("ref_name", {}).get("include", [])
    if REF_PATTERN not in includes:
        raise ValueError("candidate-attempt tag ruleset does not cover durable refs")
    rule_types = {
        rule.get("type") for rule in ruleset.get("rules", []) if isinstance(rule, dict)
    }
    if not {"deletion", "non_fast_forward"}.issubset(rule_types):
        raise ValueError("candidate-attempt tag ruleset must block deletion and updates")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    result = subprocess.run(
        ["gh", "api", f"repos/{args.repository}/rulesets?includes_parents=false"],
        text=True,
        capture_output=True,
        check=False,
    )
    try:
        if result.returncode != 0:
            raise ValueError(result.stderr.strip() or "could not read repository rulesets")
        summaries = json.loads(result.stdout)
        if not isinstance(summaries, list):
            raise ValueError("repository rulesets response must be an array")
        matches = [
            item for item in summaries
            if isinstance(item, dict) and item.get("name") == RULESET_NAME
        ]
        if len(matches) != 1 or not isinstance(matches[0].get("id"), int):
            raise ValueError(f"required ruleset is missing: {RULESET_NAME}")
        detail = subprocess.run(
            ["gh", "api", f"repos/{args.repository}/rulesets/{matches[0]['id']}"],
            text=True,
            capture_output=True,
            check=False,
        )
        if detail.returncode != 0:
            raise ValueError(detail.stderr.strip() or "could not read candidate-attempt ruleset")
        validate_rulesets([json.loads(detail.stdout)])
    except (ValueError, json.JSONDecodeError) as error:
        parser.error(str(error))
    print("candidate attempt tag ruleset: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
