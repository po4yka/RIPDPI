#!/usr/bin/env python3
"""Unit tests for scripts/ci/release_evidence_relaxations.py."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import importlib.util

ROOT = Path(__file__).resolve().parents[2]


def load_module(module_name: str, relative_path: str):
    spec = importlib.util.spec_from_file_location(module_name, ROOT / relative_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


relaxations = load_module(
    "release_evidence_relaxations", "scripts/ci/release_evidence_relaxations.py"
)


class ReleaseEvidenceRelaxationsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "policy.json"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write(self, block: object) -> Path:
        payload: dict = {"version": "dns_ipv6_killswitch_release_gates_v1"}
        if block is not None:
            payload["relaxedEvidenceRequirements"] = block
        self.path.write_text(json.dumps(payload), encoding="utf-8")
        return self.path

    def test_shipped_policy_declares_only_known_relaxations(self) -> None:
        declared = relaxations.relaxed_requirements()
        self.assertEqual(frozenset(), declared)

    def test_absent_block_means_nothing_is_relaxed(self) -> None:
        self.assertEqual(relaxations.relaxed_requirements(self.write(None)), frozenset())
        self.assertFalse(
            relaxations.is_relaxed("exact-sha-physical-run", self.write(None))
        )

    def test_declared_requirement_is_relaxed(self) -> None:
        path = self.write({"requirements": ["exact-sha-physical-run"]})
        self.assertTrue(relaxations.is_relaxed("exact-sha-physical-run", path))
        self.assertFalse(
            relaxations.is_relaxed("private-runner-configuration", path)
        )

    def test_unknown_requirement_in_policy_is_a_hard_error(self) -> None:
        path = self.write({"requirements": ["exact-sha-physical-run", "typo"]})
        with self.assertRaisesRegex(ValueError, "unknown relaxed evidence requirement"):
            relaxations.relaxed_requirements(path)

    def test_unknown_requirement_at_the_call_site_is_a_hard_error(self) -> None:
        with self.assertRaisesRegex(ValueError, "unknown evidence requirement"):
            relaxations.is_relaxed("no-such-requirement")

    def test_malformed_block_is_rejected(self) -> None:
        for block in ({"requirements": "exact-sha-physical-run"}, {"requirements": [1]}):
            with self.subTest(block=block):
                with self.assertRaises(ValueError):
                    relaxations.relaxed_requirements(self.write(block))

    def test_duplicate_requirement_is_rejected(self) -> None:
        path = self.write(
            {"requirements": ["exact-sha-physical-run", "exact-sha-physical-run"]}
        )
        with self.assertRaisesRegex(ValueError, "must not repeat"):
            relaxations.relaxed_requirements(path)

    def test_unreadable_policy_fails_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "unreadable"):
            relaxations.relaxed_requirements(Path(self.temp.name) / "missing.json")


if __name__ == "__main__":
    unittest.main()
