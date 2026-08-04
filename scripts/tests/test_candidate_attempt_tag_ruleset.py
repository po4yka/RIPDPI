#!/usr/bin/env python3

from __future__ import annotations

import unittest

from scripts.ci.check_candidate_attempt_tag_ruleset import (
    REF_PATTERN,
    RULESET_NAME,
    validate_rulesets,
)


def ruleset(**overrides):
    value = {
        "name": RULESET_NAME,
        "enforcement": "active",
        "bypass_actors": [],
        "conditions": {"ref_name": {"include": [REF_PATTERN]}},
        "rules": [{"type": "deletion"}, {"type": "non_fast_forward"}],
    }
    value.update(overrides)
    return value


class CandidateAttemptTagRulesetTest(unittest.TestCase):
    def test_accepts_active_exact_immutable_ruleset(self) -> None:
        validate_rulesets([ruleset()])

    def test_rejects_missing_pattern_bypass_and_missing_protection(self) -> None:
        cases = (
            [],
            [ruleset(bypass_actors=[{"actor_id": 1}])],
            [ruleset(conditions={"ref_name": {"include": ["refs/tags/v*"]}})],
            [ruleset(rules=[{"type": "deletion"}])],
        )
        for payload in cases:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                validate_rulesets(payload)


if __name__ == "__main__":
    unittest.main()
