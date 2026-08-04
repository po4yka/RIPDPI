#!/usr/bin/env python3
"""Tests for the checked-in release contract validator."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci.check_release_contract import ContractError, ROOT, validate_contract


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

    def test_workflow_input_drift_fails_closed(self) -> None:
        contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
        contract["candidate"]["requiredInput"] = "missing_input"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release-contract.json"
            path.write_text(json.dumps(contract), encoding="utf-8")
            with self.assertRaisesRegex(ContractError, "missing its required input"):
                validate_contract(path)


if __name__ == "__main__":
    unittest.main()
