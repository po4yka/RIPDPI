#!/usr/bin/env python3
"""Fail-closed contract tests for Android ordinary release results."""

from __future__ import annotations

import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


producer = load_module(
    "android_ordinary_results",
    ROOT / "scripts/ci/produce_android_ordinary_gate_results.py",
)
gates = load_module(
    "ordinary_results_gate_checker",
    ROOT / "scripts/ci/check_dns_ipv6_killswitch_gates.py",
)


class AndroidOrdinaryGateResultsTest(unittest.TestCase):
    source_sha = "a" * 40

    def results(self) -> dict:
        return producer.all_failure_results(self.source_sha)

    def validate(self, results: dict) -> dict:
        return gates.validate_results_document(
            gates.load_json(gates.POLICY_PATH),
            results,
            expected_source_sha=self.source_sha,
            applies_to="android-client-release",
        )

    def test_inventory_matches_exact_android_ordinary_policy_set(self) -> None:
        policy = gates.load_json(gates.POLICY_PATH)
        ordinary = gates.applicable_gate_ids(
            policy, applies_to="android-client-release"
        ) - gates.dual_vantage_gate_ids(policy, applies_to="android-client-release")
        self.assertEqual(set(producer.ORDINARY_GATE_IDS), ordinary)
        self.assertEqual(len(ordinary), 11)

    def test_producer_has_no_pluggable_or_false_green_path(self) -> None:
        self.assertFalse(producer.SOURCE_OWNED_VERIFIER_AVAILABLE)
        self.assertFalse(hasattr(producer, "APPROVED_COLLECTORS"))
        self.assertFalse(hasattr(producer, "run_approved_collector"))
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            producer.validate_pass_results({})

    def test_complete_structured_failure_is_checker_compatible(self) -> None:
        results = self.results()
        self.assertEqual(self.validate(results), results)
        self.assertEqual(set(results["gateResults"]), set(producer.ORDINARY_GATE_IDS))
        self.assertTrue(
            all(value["state"] == "FAIL" for value in results["gateResults"].values())
        )
        evaluation = gates.evaluate_results(
            gates.load_json(gates.POLICY_PATH),
            results,
            applies_to="android-client-release",
        )
        self.assertTrue(
            any(
                producer.UNAVAILABLE_CODE in violation
                for violation in evaluation["violations"]
            )
        )

    def test_output_is_deterministic_and_canonical(self) -> None:
        first = self.results()
        second = self.results()
        self.assertEqual(
            producer.canonical_json_bytes(first), producer.canonical_json_bytes(second)
        )
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            producer.write_canonical_json(output, first)
            self.assertEqual(output.read_bytes(), producer.canonical_json_bytes(first))
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)

    def test_checker_rejects_object_pass_even_with_forged_metadata(self) -> None:
        results = self.results()
        results["gateResults"][producer.ORDINARY_GATE_IDS[0]] = {"state": "PASS"}
        results["producerAttestation"] = {
            "forged": "all public hashes and arbitrary artifacts"
        }
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            self.validate(results)

    def test_checker_rejects_legacy_string_pass(self) -> None:
        results = self.results()
        results["gateResults"] = {
            gate_id: "PASS" for gate_id in producer.ORDINARY_GATE_IDS
        }
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            self.validate(results)

    def test_checker_rejects_ordinary_pass_inside_full_policy_document(self) -> None:
        policy = gates.load_json(gates.POLICY_PATH)
        results = {
            "appliesTo": "android-client-release",
            "gateResults": {gate["id"]: "PASS" for gate in policy["gates"]},
            "sourceSha": self.source_sha,
            "version": producer.RESULTS_VERSION,
        }
        with self.assertRaisesRegex(ValueError, producer.UNAVAILABLE_CODE):
            self.validate(results)

        results = self.results()
        results["gateResults"][producer.ORDINARY_GATE_IDS[0]] = {"state": "PASS"}
        results["gateResults"]["unexpected-extra-gate"] = {
            "reason": "extra",
            "state": "FAIL",
        }
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            self.validate(results)

    def test_checker_rejects_partial_or_non_structured_failure(self) -> None:
        results = self.results()
        results["gateResults"].pop(producer.ORDINARY_GATE_IDS[-1])
        with self.assertRaisesRegex(ValueError, "exactly cover"):
            self.validate(results)

        results = self.results()
        results["gateResults"][producer.ORDINARY_GATE_IDS[0]] = "FAIL"
        with self.assertRaisesRegex(ValueError, "structured all-FAIL"):
            self.validate(results)

    def test_current_source_rejects_untracked_files(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(["git", "init", "-q"], cwd=repository, check=True)
            subprocess.run(
                ["git", "config", "user.email", "test@example.invalid"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "Test"], cwd=repository, check=True
            )
            (repository / "tracked").write_text("tracked\n", encoding="utf-8")
            subprocess.run(["git", "add", "tracked"], cwd=repository, check=True)
            subprocess.run(["git", "commit", "-qm", "test"], cwd=repository, check=True)
            self.assertRegex(producer.current_source_sha(repository), r"^[0-9a-f]{40}$")
            (repository / "untracked").write_text("dirty\n", encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "clean source checkout"):
                producer.current_source_sha(repository)

    def test_cli_replaces_stale_pass_with_complete_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            output.write_text('{"stale":"PASS"}\n', encoding="utf-8")
            with (
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value=self.source_sha
                ),
            ):
                status = producer.main(["--output", str(output)])
            self.assertEqual(status, 1)
            results = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(self.validate(results), results)
            self.assertTrue(
                all(
                    producer.UNAVAILABLE_CODE in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_cli_replaces_stale_pass_on_dirty_or_unexpected_source_failure(
        self,
    ) -> None:
        failures = (
            producer.EvidenceError("SOURCE_DIRTY", "dirty checkout"),
            RuntimeError("unexpected"),
        )
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                with tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / "results.json"
                    output.write_text('{"stale":"PASS"}\n', encoding="utf-8")
                    with (
                        mock.patch.object(
                            producer,
                            "current_head_sha",
                            return_value=self.source_sha,
                        ),
                        mock.patch.object(
                            producer, "current_source_sha", side_effect=failure
                        ),
                    ):
                        status = producer.main(["--output", str(output)])
                    self.assertEqual(status, 1)
                    results = json.loads(output.read_text(encoding="utf-8"))
                    self.assertEqual(self.validate(results), results)
                    self.assertNotIn("stale", results)
                    self.assertTrue(
                        all(
                            value["state"] == "FAIL"
                            for value in results["gateResults"].values()
                        )
                    )

    def test_cli_fails_closed_when_head_changes_during_validation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            with (
                mock.patch.object(
                    producer, "current_head_sha", return_value=self.source_sha
                ),
                mock.patch.object(
                    producer, "current_source_sha", return_value="b" * 40
                ),
            ):
                status = producer.main(["--output", str(output)])
            self.assertEqual(status, 1)
            results = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(
                all(
                    "SOURCE_CHANGED" in value["reason"]
                    for value in results["gateResults"].values()
                )
            )

    def test_cli_removes_stale_pass_when_head_cannot_be_bound(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "results.json"
            output.write_text('{"stale":"PASS"}\n', encoding="utf-8")
            with mock.patch.object(
                producer,
                "current_head_sha",
                side_effect=producer.EvidenceError("SOURCE_INVALID", "invalid head"),
            ):
                status = producer.main(["--output", str(output)])
            self.assertEqual(status, 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
