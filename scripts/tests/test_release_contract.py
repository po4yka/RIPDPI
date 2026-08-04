#!/usr/bin/env python3
"""Tests for the checked-in release contract validator."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci.check_release_contract import (
    ContractError,
    ROOT,
    validate_contract,
    validate_workflow_trigger,
)


CONTRACT = ROOT / "quality/release-gates/release-contract.json"


class ReleaseContractTest(unittest.TestCase):
    def test_checked_in_contract_matches_workflows_and_guidance(self) -> None:
        validate_contract()

    def test_missing_guidance_path_fails_closed(self) -> None:
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        contract["guidance"].append("docs/does-not-exist.md")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release-contract.json"
            path.write_text(json.dumps(contract), encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "does not exist"):
                validate_contract(path)

    def test_workflow_dispatch_rejects_extra_or_weakened_inputs(self) -> None:
        expected = {
            "event": "workflow_dispatch",
            "inputs": {"release_tag": {"required": True, "type": "string"}},
        }
        for source, message in (
            (
                "on:\n  workflow_dispatch:\n    inputs:\n      release_tag:\n        required: false\n        type: string\n",
                "must be required",
            ),
            (
                "on:\n  workflow_dispatch:\n    inputs:\n      release_tag:\n        required: true\n        type: string\n      extra:\n        required: true\n        type: string\n",
                "inputs must be exactly",
            ),
            (
                "on:\n  workflow_dispatch:\n    inputs:\n      release_tag:\n        required: true\n        type: string\n  push:\n",
                "events must be exactly",
            ),
        ):
            with self.subTest(message=message):
                with self.assertRaisesRegex(ContractError, message):
                    validate_workflow_trigger(source, expected, "candidate.trigger")

    def test_tag_publication_rejects_extra_trigger(self) -> None:
        source = 'on:\n  push:\n    tags: ["v*"]\n  workflow_dispatch:\n'
        with self.assertRaisesRegex(ContractError, "events must be exactly"):
            validate_workflow_trigger(
                source,
                {"event": "push", "tags": ["v*"]},
                "publication.trigger",
            )


if __name__ == "__main__":
    unittest.main()
