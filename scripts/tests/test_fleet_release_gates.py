#!/usr/bin/env python3
"""Unit tests for scripts/ci/check_fleet_release_gates.py."""
from __future__ import annotations

import copy
import importlib.util
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


fleet = load_module(
    "check_fleet_release_gates",
    "scripts/ci/check_fleet_release_gates.py",
)


def _pass_results(policy: dict, gate_set: str) -> dict:
    return {gate: "PASS" for gate in policy["gateSets"][gate_set]["requires"]}


class FleetReleaseGatesPolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = fleet.load_json(fleet.POLICY_PATH)

    def test_repo_policy_is_valid(self) -> None:
        summary = fleet.validate_policy(self.policy)
        for cadence in fleet.REQUIRED_CADENCE_CHECKS:
            self.assertIn(cadence, summary["cadences"])
        for gate_set in fleet.REQUIRED_GATE_SETS:
            self.assertIn(gate_set, summary["gateSets"])
        for control in fleet.REQUIRED_DEPLOYMENT_CONTROLS:
            self.assertIn(control, summary["deploymentControls"])
        for condition in fleet.REQUIRED_NOSHIP_CONDITIONS:
            self.assertIn(condition, summary["noShipConditions"])
        for condition in fleet.REQUIRED_WARNONLY_CONDITIONS:
            self.assertIn(condition, summary["warnOnlyConditions"])

    def test_wrong_version_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["version"] = "fleet_release_cadence_policy_v0"
        with self.assertRaisesRegex(ValueError, "unexpected policy version"):
            fleet.validate_policy(broken)

    def test_missing_daily_check_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["cadences"]["daily"]["checks"] = [
            c for c in broken["cadences"]["daily"]["checks"] if c != "cert-expiry"
        ]
        with self.assertRaisesRegex(ValueError, "cadence 'daily' is missing"):
            fleet.validate_policy(broken)

    def test_missing_weekly_check_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["cadences"]["weekly"]["checks"] = [
            c for c in broken["cadences"]["weekly"]["checks"] if c != "dns-leak"
        ]
        with self.assertRaisesRegex(ValueError, "cadence 'weekly' is missing"):
            fleet.validate_policy(broken)

    def test_missing_production_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gateSets"]["production"]["requires"] = [
            g
            for g in broken["gateSets"]["production"]["requires"]
            if g != "android-kill-switch-primary-profile"
        ]
        with self.assertRaisesRegex(ValueError, "gate set 'production' is missing"):
            fleet.validate_policy(broken)

    def test_missing_relay_deployment_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gateSets"]["relay-deployment"]["requires"] = [
            g
            for g in broken["gateSets"]["relay-deployment"]["requires"]
            if g != "disposable-relay-rebuild"
        ]
        with self.assertRaisesRegex(ValueError, "gate set 'relay-deployment' is missing"):
            fleet.validate_policy(broken)

    def test_missing_client_release_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gateSets"]["client-release"]["requires"] = [
            g
            for g in broken["gateSets"]["client-release"]["requires"]
            if g != "logcat-no-secrets"
        ]
        with self.assertRaisesRegex(ValueError, "gate set 'client-release' is missing"):
            fleet.validate_policy(broken)

    def test_missing_noship_condition_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["noShipPolicy"]["conditions"] = [
            c for c in broken["noShipPolicy"]["conditions"] if c != "dns-leak"
        ]
        with self.assertRaisesRegex(ValueError, "noShipPolicy.conditions is missing"):
            fleet.validate_policy(broken)

    def test_missing_warnonly_condition_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["warnOnlyPolicy"]["conditions"] = [
            c
            for c in broken["warnOnlyPolicy"]["conditions"]
            if c != "partial-hysteria2-udp-failure"
        ]
        with self.assertRaisesRegex(ValueError, "warnOnlyPolicy.conditions is missing"):
            fleet.validate_policy(broken)

    def test_overlap_between_noship_and_warnonly_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["warnOnlyPolicy"]["conditions"].append("dns-leak")
        with self.assertRaisesRegex(ValueError, "cannot be both no-ship and warn-only"):
            fleet.validate_policy(broken)

    def test_missing_deployment_control_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["deploymentPlane"]["requiredControls"] = [
            control
            for control in broken["deploymentPlane"]["requiredControls"]
            if control["id"] != "certificate-rotation-drill"
        ]
        with self.assertRaisesRegex(ValueError, "deploymentPlane.requiredControls is missing"):
            fleet.validate_policy(broken)

    def test_missing_deployment_doc_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["deploymentPlane"]["documentation"] = "docs/missing-relay-deployment-operations.md"
        with self.assertRaisesRegex(ValueError, "deploymentPlane.documentation does not exist"):
            fleet.validate_policy(broken)

    def test_deployment_doc_without_required_heading_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["deploymentPlane"]["documentation"] = "README.md"
        with self.assertRaisesRegex(ValueError, "documentation is missing heading"):
            fleet.validate_policy(broken)

    def test_deployment_doc_anchor_must_match_heading(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["deploymentPlane"]["requiredControls"][0]["docAnchor"] = "certificate-rollover"
        with self.assertRaisesRegex(ValueError, "docAnchor must be"):
            fleet.validate_policy(broken)

    def test_deployment_control_must_be_noship(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["noShipPolicy"]["conditions"] = [
            condition
            for condition in broken["noShipPolicy"]["conditions"]
            if condition != "incident-playbook-ready"
        ]
        with self.assertRaisesRegex(ValueError, "noShipPolicy.conditions is missing"):
            fleet.validate_policy(broken)


class FleetReleaseGatesEvaluationTest(unittest.TestCase):
    def setUp(self) -> None:
        self.policy = fleet.load_json(fleet.POLICY_PATH)
        fleet.validate_policy(self.policy)

    def test_all_pass_production(self) -> None:
        results = _pass_results(self.policy, "production")
        evaluation = fleet.evaluate_gate_set(self.policy, "production", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "PASS")
        self.assertEqual(evaluation["blocking"], [])

    def test_all_pass_relay_deployment(self) -> None:
        results = _pass_results(self.policy, "relay-deployment")
        evaluation = fleet.evaluate_gate_set(self.policy, "relay-deployment", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "PASS")
        self.assertEqual(evaluation["blocking"], [])

    def test_noship_fail_blocks(self) -> None:
        results = _pass_results(self.policy, "production")
        results["dns-leak"] = "FAIL"
        evaluation = fleet.evaluate_gate_set(self.policy, "production", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "FAIL")
        self.assertEqual(len(evaluation["blocking"]), 1)
        self.assertIn("dns-leak", evaluation["blocking"][0])

    def test_missing_gate_blocks(self) -> None:
        results = _pass_results(self.policy, "production")
        del results["dns-leak"]
        evaluation = fleet.evaluate_gate_set(self.policy, "production", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "FAIL")
        self.assertIn("missing result", evaluation["blocking"][0])

    def test_invalid_state_blocks(self) -> None:
        results = _pass_results(self.policy, "production")
        results["non-ru-smoke"] = "MAYBE"
        evaluation = fleet.evaluate_gate_set(self.policy, "production", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "FAIL")
        self.assertIn("invalid state", evaluation["blocking"][0])

    def test_warn_only_condition_is_demoted_not_blocking(self) -> None:
        # A warn-only condition appearing as a gate FAILs to WARN, not block.
        # Build a synthetic gate set that includes a warn-only condition.
        policy = copy.deepcopy(self.policy)
        policy["gateSets"]["staging"]["requires"].append("partial-hysteria2-udp-failure")
        results = {gate: "PASS" for gate in policy["gateSets"]["staging"]["requires"]}
        results["partial-hysteria2-udp-failure"] = "FAIL"
        evaluation = fleet.evaluate_gate_set(policy, "staging", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "PASS")
        self.assertEqual(evaluation["blocking"], [])
        demoted = [r for r in evaluation["rows"] if r["gate"] == "partial-hysteria2-udp-failure"]
        self.assertEqual(demoted[0]["state"], "WARN")

    def test_unknown_gate_set_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "unknown gate set"):
            fleet.evaluate_gate_set(self.policy, "nonexistent", {})

    def test_na_state_without_scope_blocks(self) -> None:
        results = _pass_results(self.policy, "client-release")
        results["logcat-no-secrets"] = "N/A"
        evaluation = fleet.evaluate_gate_set(self.policy, "client-release", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "FAIL")
        self.assertIn("outOfScope", evaluation["blocking"][0])

    def test_scoped_out_of_scope_na_is_allowed(self) -> None:
        results = _pass_results(self.policy, "client-release")
        results["logcat-no-secrets"] = {
            "state": "N/A",
            "outOfScope": True,
            "reason": "The logcat collection is outside this release lane.",
            "gateSets": ["client-release"],
        }
        evaluation = fleet.evaluate_gate_set(self.policy, "client-release", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "PASS")
        row = [r for r in evaluation["rows"] if r["gate"] == "logcat-no-secrets"][0]
        self.assertIn("out-of-scope", row["note"])

    def test_out_of_scope_na_for_wrong_gate_set_blocks(self) -> None:
        results = _pass_results(self.policy, "client-release")
        results["logcat-no-secrets"] = {
            "state": "N/A",
            "outOfScope": True,
            "reason": "Only skipped for staging.",
            "gateSets": ["staging"],
        }
        evaluation = fleet.evaluate_gate_set(self.policy, "client-release", {"gateResults": results})
        self.assertEqual(evaluation["verdict"], "FAIL")
        self.assertIn("gateSet=client-release", evaluation["blocking"][0])


class FleetReleaseGatesMainTest(unittest.TestCase):
    def test_main_validates_repo_policy(self) -> None:
        self.assertEqual(fleet.main([]), 0)

    def test_main_report_mode(self) -> None:
        self.assertEqual(fleet.main(["--report"]), 0)


if __name__ == "__main__":
    unittest.main()
