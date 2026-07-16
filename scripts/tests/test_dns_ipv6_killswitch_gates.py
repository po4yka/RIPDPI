#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_dns_ipv6_killswitch_gates.py."""
from __future__ import annotations

import copy
import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


def load_module(module_name: str, relative_path: str):
    root = Path(__file__).resolve().parents[2]
    module_path = root / relative_path
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


gates = load_module(
    "check_dns_ipv6_killswitch_gates",
    "scripts/ci/check_dns_ipv6_killswitch_gates.py",
)


class DnsIpv6KillSwitchGatesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = gates.load_json(gates.POLICY_PATH)

    def results_document(self, *, source_sha: str = "a" * 40) -> dict:
        return {
            "version": "dns_ipv6_killswitch_results_v1",
            "sourceSha": source_sha,
            "appliesTo": "android-client-release",
            "gateResults": {g["id"]: "PASS" for g in self.policy["gates"]},
        }

    def test_repo_policy_is_valid(self) -> None:
        summary = gates.validate_policy(self.policy)
        self.assertEqual(summary["gateCount"], len(self.policy["gates"]))
        for gate_id in gates.REQUIRED_GATE_IDS:
            self.assertIn(gate_id, summary["gateIds"])
        for category in gates.REQUIRED_CATEGORIES:
            self.assertIn(category, summary["categories"])
        for classification in gates.REQUIRED_NOSHIP_CLASSIFICATIONS:
            self.assertIn(classification, summary["noShipClassifications"])

    def test_every_gate_is_noship(self) -> None:
        for gate in self.policy["gates"]:
            self.assertTrue(
                gate.get("noShip") is True,
                msg=f"gate {gate['id']} must be noShip=true",
            )

    def test_missing_required_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gates"] = [
            g for g in broken["gates"] if g["id"] != "killswitch-core-crash"
        ]
        with self.assertRaisesRegex(ValueError, "missing required gates"):
            gates.validate_policy(broken)

    def test_non_noship_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gates"][0]["noShip"] = False
        with self.assertRaisesRegex(ValueError, "not marked noShip"):
            gates.validate_policy(broken)

    def test_unknown_category_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gates"][0]["category"] = "totally-unknown"
        with self.assertRaisesRegex(ValueError, "unknown category"):
            gates.validate_policy(broken)

    def test_bad_failure_classification_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gates"][0]["failureClassification"] = "cosmetic"
        with self.assertRaisesRegex(ValueError, "failureClassification"):
            gates.validate_policy(broken)

    def test_wrong_version_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["version"] = "dns_ipv6_killswitch_release_gates_v0"
        with self.assertRaisesRegex(ValueError, "unexpected policy version"):
            gates.validate_policy(broken)

    def test_noship_policy_missing_classification_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["noShipPolicy"]["failureClassifications"] = ["dns-leak"]
        with self.assertRaisesRegex(ValueError, "noShipPolicy"):
            gates.validate_policy(broken)

    def test_results_all_pass(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        evaluation = gates.evaluate_results(self.policy, {"gateResults": results})
        self.assertEqual(evaluation["violations"], [])
        self.assertEqual(evaluation["evaluated"], len(self.policy["gates"]))

    def test_results_document_must_match_expected_source_and_scope(self) -> None:
        validated = gates.validate_results_document(
            self.policy,
            self.results_document(),
            expected_source_sha="a" * 40,
            applies_to="android-client-release",
        )
        self.assertEqual(validated["sourceSha"], "a" * 40)

        with self.assertRaisesRegex(ValueError, "sourceSha"):
            gates.validate_results_document(
                self.policy,
                self.results_document(source_sha="b" * 40),
                expected_source_sha="a" * 40,
                applies_to="android-client-release",
            )

        wrong_scope = self.results_document()
        wrong_scope["appliesTo"] = "fleet-profile-rollout"
        with self.assertRaisesRegex(ValueError, "appliesTo"):
            gates.validate_results_document(
                self.policy,
                wrong_scope,
                expected_source_sha="a" * 40,
                applies_to="android-client-release",
            )

    def test_results_fail_on_noship_gate_is_violation(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["dns-virtual-vpn-resolver"] = "FAIL"
        evaluation = gates.evaluate_results(self.policy, {"gateResults": results})
        self.assertEqual(len(evaluation["violations"]), 1)
        self.assertIn("dns-virtual-vpn-resolver", evaluation["violations"][0])

    def test_results_warn_on_noship_gate_is_violation(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["killswitch-forced-disconnect"] = "WARN"
        evaluation = gates.evaluate_results(self.policy, {"gateResults": results})
        self.assertEqual(len(evaluation["violations"]), 1)
        self.assertIn("killswitch-forced-disconnect", evaluation["violations"][0])

    def test_results_malformed_state_and_unknown_gate_are_violations(self) -> None:
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["killswitch-forced-disconnect"] = {"state": 42}
        results["not-a-policy-gate"] = "PASS"
        evaluation = gates.evaluate_results(self.policy, {"gateResults": results})
        self.assertEqual(len(evaluation["violations"]), 2)
        self.assertTrue(any("missing string state" in item for item in evaluation["violations"]))
        self.assertTrue(any("unknown gate id" in item for item in evaluation["violations"]))

    def test_results_missing_gate_is_violation(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        del results["ipv4only-no-direct-ipv6"]
        evaluation = gates.evaluate_results(self.policy, {"gateResults": results})
        self.assertEqual(len(evaluation["violations"]), 1)
        self.assertIn("missing result", evaluation["violations"][0])

    def test_results_na_without_scope_is_violation(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["killswitch-android-always-on-block"] = "N/A"
        evaluation = gates.evaluate_results(self.policy, {"gateResults": results})
        self.assertEqual(len(evaluation["violations"]), 1)
        self.assertIn("outOfScope", evaluation["violations"][0])

    def test_results_scoped_out_of_scope_na_is_allowed(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["killswitch-android-always-on-block"] = {
            "state": "N/A",
            "outOfScope": True,
            "reason": "Always-on VPN is not available in this managed profile.",
            "appliesTo": ["android-client-release"],
        }
        evaluation = gates.evaluate_results(
            self.policy,
            {"gateResults": results},
            applies_to="android-client-release",
        )
        self.assertEqual(evaluation["violations"], [])

    def test_results_out_of_scope_na_missing_reason_is_violation(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["killswitch-android-always-on-block"] = {
            "state": "N/A",
            "outOfScope": True,
            "appliesTo": ["android-client-release"],
        }
        evaluation = gates.evaluate_results(
            self.policy,
            {"gateResults": results},
            applies_to="android-client-release",
        )
        self.assertEqual(len(evaluation["violations"]), 1)
        self.assertIn("missing reason", evaluation["violations"][0])

    def test_results_out_of_scope_na_wrong_applies_to_is_violation(self) -> None:
        gates.validate_policy(self.policy)
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        results["killswitch-android-always-on-block"] = {
            "state": "N/A",
            "outOfScope": True,
            "reason": "Only evaluated for fleet rollout.",
            "appliesTo": ["fleet-profile-rollout"],
        }
        evaluation = gates.evaluate_results(
            self.policy,
            {"gateResults": results},
            applies_to="android-client-release",
        )
        self.assertEqual(len(evaluation["violations"]), 1)
        self.assertIn("appliesTo=android-client-release", evaluation["violations"][0])

    def test_main_requires_results_or_explicit_policy_only_mode(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gates.main([]), 1)

    def test_main_policy_only_mode_validates_repo_policy(self) -> None:
        self.assertEqual(gates.main(["--policy-only"]), 0)

    def test_main_requires_release_scope_with_results(self) -> None:
        results = {g["id"]: "PASS" for g in self.policy["gates"]}
        with tempfile.TemporaryDirectory() as directory:
            results_path = Path(directory) / "results.json"
            results_path.write_text(json.dumps({"gateResults": results}), encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(gates.main(["--results", str(results_path)]), 1)

    def test_main_requires_expected_source_sha_with_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            results_path = Path(directory) / "results.json"
            results_path.write_text(json.dumps(self.results_document()), encoding="utf-8")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(
                    gates.main(
                        [
                            "--results",
                            str(results_path),
                            "--applies-to",
                            "android-client-release",
                        ]
                    ),
                    1,
                )

    def test_main_accepts_complete_exact_sha_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            results_path = Path(directory) / "results.json"
            results_path.write_text(json.dumps(self.results_document()), encoding="utf-8")
            self.assertEqual(
                gates.main(
                    [
                        "--results",
                        str(results_path),
                        "--applies-to",
                        "android-client-release",
                        "--expected-source-sha",
                        "a" * 40,
                    ]
                ),
                0,
            )

    def test_main_rejects_missing_results_file(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(
                gates.main(
                    [
                        "--results",
                        "/does/not/exist/results.json",
                        "--applies-to",
                        "android-client-release",
                        "--expected-source-sha",
                        "a" * 40,
                    ]
                ),
                1,
            )

    def test_cli_rejects_malformed_results_json(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            results_path = Path(directory) / "results.json"
            results_path.write_text("{not-json", encoding="utf-8")
            completed = subprocess.run(
                [
                    sys.executable,
                    str(Path(__file__).resolve().parents[1] / "ci/check_dns_ipv6_killswitch_gates.py"),
                    "--results",
                    str(results_path),
                    "--applies-to",
                    "android-client-release",
                    "--expected-source-sha",
                    "a" * 40,
                ],
                capture_output=True,
                check=False,
                text=True,
            )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("gate check failed", completed.stderr)

    def test_main_rejects_scope_in_policy_only_mode(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(
                gates.main(["--policy-only", "--applies-to", "android-client-release"]),
                1,
            )

    def test_main_report_mode(self) -> None:
        self.assertEqual(gates.main(["--policy-only", "--report"]), 0)

    def test_ci_policy_check_and_release_evidence_wiring_are_distinct(self) -> None:
        root = Path(__file__).resolve().parents[2]
        ci = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        release = (root / ".github/workflows/release.yml").read_text(encoding="utf-8")
        evidence = (root / ".github/workflows/dns-ipv6-killswitch-evidence.yml").read_text(encoding="utf-8")

        self.assertIn("check_dns_ipv6_killswitch_gates.py --policy-only", ci)
        self.assertIn("actions/download-artifact@", release)
        self.assertIn("dns-ipv6-killswitch-release-evidence", release)
        self.assertIn("--results", release)
        self.assertIn("--applies-to android-client-release", release)
        self.assertIn('--expected-source-sha "$GITHUB_SHA"', release)
        self.assertIn("run-id:", release)

        self.assertIn("ref: ${{ inputs.source_sha }}", evidence)
        self.assertIn('[[ ! "$SOURCE_SHA" =~ ^[0-9a-f]{40}$ ]]', evidence)
        self.assertIn('--expected-source-sha "$SOURCE_SHA"', evidence)
        self.assertNotIn('--expected-source-sha "${{ inputs.source_sha }}"', evidence)
        self.assertIn("check_dns_ipv6_killswitch_gates.py", evidence)
        self.assertIn("--results", evidence)
        self.assertIn("actions/upload-artifact@", evidence)
        self.assertIn("dns-ipv6-killswitch-release-evidence", evidence)


if __name__ == "__main__":
    unittest.main()
