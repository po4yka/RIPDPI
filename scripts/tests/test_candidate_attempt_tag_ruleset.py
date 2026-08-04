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
        "target": "tag",
        "enforcement": "active",
        "bypass_actors": [],
        "conditions": {"ref_name": {"include": [REF_PATTERN], "exclude": []}},
        "rules": [{"type": "deletion"}, {"type": "update"}],
    }
    value.update(overrides)
    return value


class CandidateAttemptTagRulesetTest(unittest.TestCase):
    def test_accepts_active_exact_immutable_ruleset(self) -> None:
        validate_rulesets([ruleset()])

    def test_rejects_missing_pattern_bypass_and_missing_protection(self) -> None:
        missing_bypass = ruleset()
        del missing_bypass["bypass_actors"]
        cases = (
            [],
            [missing_bypass],
            [ruleset(bypass_actors=[{"actor_id": 1}])],
            [ruleset(bypass_actors=None)],
            [ruleset(bypass_actors={})],
            [ruleset(target="branch")],
            [ruleset(conditions={"ref_name": {"include": ["refs/tags/v*"], "exclude": []}})],
            [ruleset(conditions={"ref_name": {"include": [REF_PATTERN], "exclude": [REF_PATTERN]}})],
            [ruleset(rules=[{"type": "deletion"}])],
            [ruleset(rules=[{"type": "deletion"}, {"type": "non_fast_forward"}])],
        )
        for payload in cases:
            with self.subTest(payload=payload), self.assertRaises(ValueError):
                validate_rulesets(payload)


if __name__ == "__main__":
    unittest.main()
