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
import time
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
        self.temp = tempfile.TemporaryDirectory()
        self.original_producer_policy_path = (
            gates.network_evidence_manifest.PRODUCER_POLICY_PATH
        )
        producer_policy_path = Path(self.temp.name) / "network-evidence-producers.json"
        gates.network_evidence_manifest.write_canonical_json(
            producer_policy_path,
            {
                "version": "network_evidence_producers_v2",
                "clientCollectorSha256": ["3" * 64],
                "observerCollectorSha256": ["4" * 64],
                "workloadSha256": ["9" * 64],
                "clientArtifactSha256": ["8" * 64],
                "testArtifactSha256": ["7" * 64],
            },
        )
        gates.network_evidence_manifest.PRODUCER_POLICY_PATH = producer_policy_path

    def tearDown(self) -> None:
        gates.network_evidence_manifest.PRODUCER_POLICY_PATH = (
            self.original_producer_policy_path
        )
        self.temp.cleanup()

    def results_document(self, *, source_sha: str = "a" * 40) -> dict:
        return {
            "version": "dns_ipv6_killswitch_results_v1",
            "sourceSha": source_sha,
            "appliesTo": "android-client-release",
            "gateResults": {g["id"]: "PASS" for g in self.policy["gates"]},
        }

    def ordinary_results_document(self, *, source_sha: str = "a" * 40) -> dict:
        document = self.results_document(source_sha=source_sha)
        evidence_ids = gates.dual_vantage_gate_ids(
            self.policy, applies_to="android-client-release"
        )
        document["gateResults"] = {
            gate_id: result
            for gate_id, result in document["gateResults"].items()
            if gate_id not in evidence_ids
        }
        return document

    def write_ordinary_results(self, directory: Path) -> Path:
        path = directory / "results.json"
        path.write_text(json.dumps(self.ordinary_results_document()), encoding="utf-8")
        return path

    def write_ordinary_failure_results(self, directory: Path) -> Path:
        document = self.ordinary_results_document()
        document["gateResults"] = {
            gate_id: {"reason": "physical ordinary oracle unavailable", "state": "FAIL"}
            for gate_id in document["gateResults"]
        }
        path = directory / "results.json"
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def evidence_bundle(
        self,
        directory: Path,
        *,
        failed_gate: str | None = None,
        capture_error_gate: str | None = None,
    ) -> Path:
        now = int(time.time())
        correlation_id = "b" * 64
        evidence_gate_ids = gates.dual_vantage_gate_ids(
            self.policy, applies_to="android-client-release"
        )
        plan = {
            "version": gates.network_evidence_manifest.PLAN_VERSION,
            "sourceSha": "a" * 40,
            "correlationId": correlation_id,
            "clientArtifactSha256": "8" * 64,
            "testArtifactSha256": "7" * 64,
            "windows": [
                {
                    "id": gate_id,
                    "kind": gates.network_evidence_manifest.expected_kind(gate_id),
                    "startedAtEpoch": now - 20,
                    "finishedAtEpoch": now - 10,
                    "actionMarkerSha256": gates.network_evidence_manifest.derive_marker(
                        correlation_id,
                        gate_id,
                        gates.network_evidence_manifest.expected_kind(gate_id),
                        "action",
                    ),
                    "outcomeMarkerSha256": gates.network_evidence_manifest.derive_marker(
                        correlation_id,
                        gate_id,
                        gates.network_evidence_manifest.expected_kind(gate_id),
                        "outcome",
                    ),
                }
                for gate_id in sorted(evidence_gate_ids)
            ],
        }
        plan_sha256 = gates.network_evidence_manifest.sha256_bytes(
            gates.network_evidence_manifest.canonical_json_bytes(plan)
        )

        def observation(role: str) -> dict:
            return {
                "version": "network_evidence_observation_v2",
                "sourceSha": "a" * 40,
                "correlationId": correlation_id,
                "role": role,
                "vantageIdSha256": ("1" if role == "client-underlay" else "2") * 64,
                "networkIdSha256": ("5" if role == "client-underlay" else "6") * 64,
                "collectorSha256": ("3" if role == "client-underlay" else "4") * 64,
                "clientArtifactSha256": "8" * 64,
                "testArtifactSha256": "7" * 64,
                "scenarioPlanSha256": plan_sha256,
                "captureStartedAtEpoch": now - 21,
                "captureFinishedAtEpoch": now - 8,
                "rawCaptureSha256": ("c" if role == "client-underlay" else "d") * 64,
                "windows": [
                    {
                        **window,
                        "expectedPacketCount": 2,
                        "unexpectedPacketCount": 1
                        if window["id"] == failed_gate
                        else 0,
                        "captureErrorCount": (
                            1 if window["id"] == capture_error_gate else 0
                        ),
                        "actionObservedCount": 1,
                        "outcomeObservedCount": 1,
                    }
                    for window in plan["windows"]
                ],
            }

        client_path = directory / "client-observation.json"
        observer_path = directory / "observer-observation.json"
        gates.network_evidence_manifest.write_canonical_json(
            client_path, observation("client-underlay")
        )
        gates.network_evidence_manifest.write_canonical_json(
            observer_path, observation("external-observer")
        )
        manifest = gates.network_evidence_manifest.assemble_manifest(
            client_path=client_path,
            observer_path=observer_path,
            source_sha="a" * 40,
            applies_to="android-client-release",
            generated_at_epoch=now - 7,
            execution_kind="local",
            execution_id="unit-test",
            execution_attempt=1,
            execution_definition="local",
            runner_sha256=gates.network_evidence_manifest.sha256_bytes(
                gates.network_evidence_manifest.RUNNER_PATH.read_bytes()
            ),
            validator_sha256=gates.network_evidence_manifest.sha256_bytes(
                Path(gates.network_evidence_manifest.__file__).read_bytes()
            ),
            policy_sha256=gates.network_evidence_manifest.sha256_bytes(
                gates.network_evidence_manifest.POLICY_PATH.read_bytes()
            ),
            producer_policy_sha256=gates.network_evidence_manifest.sha256_bytes(
                gates.network_evidence_manifest.PRODUCER_POLICY_PATH.read_bytes()
            ),
            workload_sha256="9" * 64,
            client_artifact_sha256="8" * 64,
            test_artifact_sha256="7" * 64,
        )
        manifest_path = directory / "manifest.json"
        gates.network_evidence_manifest.write_canonical_json(manifest_path, manifest)
        return manifest_path

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

    def test_startup_barrier_is_a_mandatory_android_noship_gate(self) -> None:
        gate_id = "killswitch-tun-establish-native-ready"
        matching = [gate for gate in self.policy["gates"] if gate["id"] == gate_id]

        self.assertIn(gate_id, gates.REQUIRED_GATE_IDS)
        self.assertEqual(len(matching), 1)
        self.assertEqual(matching[0]["category"], "kill-switch")
        self.assertTrue(matching[0]["noShip"])
        self.assertEqual(matching[0]["appliesTo"], ["android-client-release"])
        self.assertEqual(matching[0]["evidenceSource"], "dual-vantage-network-manifest")

    def test_dns_and_startup_windows_require_dual_vantage_evidence(self) -> None:
        evidence_ids = gates.dual_vantage_gate_ids(
            self.policy, applies_to="android-client-release"
        )
        expected = {
            gate["id"]
            for gate in self.policy["gates"]
            if gate["category"] in {"dns-leak", "synthetic-authoritative-dns"}
        }
        expected.add("killswitch-tun-establish-native-ready")
        self.assertEqual(evidence_ids, expected)
        self.assertEqual(len(evidence_ids), 10)
        self.assertEqual(
            gates.dual_vantage_gate_ids(
                self.policy, applies_to="fleet-profile-rollout"
            ),
            set(),
        )
        self.assertEqual(
            len(
                gates.applicable_gate_ids(
                    self.policy, applies_to="fleet-profile-rollout"
                )
            ),
            19,
        )

    def test_missing_required_gate_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gates"] = [
            g for g in broken["gates"] if g["id"] != "killswitch-core-crash"
        ]
        with self.assertRaisesRegex(ValueError, "missing required gates"):
            gates.validate_policy(broken)

    def test_android_dual_vantage_mapping_cannot_remove_dns_gate(self) -> None:
        broken = copy.deepcopy(self.policy)
        gate = next(g for g in broken["gates"] if g["id"] == "dns-core-crash-behavior")
        del gate["evidenceSources"]["android-client-release"]
        with self.assertRaisesRegex(ValueError, "android dual-vantage gate mapping"):
            gates.validate_policy(broken)

    def test_android_dual_vantage_mapping_cannot_override_dns_as_ordinary(self) -> None:
        broken = copy.deepcopy(self.policy)
        gate = next(g for g in broken["gates"] if g["id"] == "dns-core-crash-behavior")
        gate["evidenceSources"]["android-client-release"] = "ordinary-results"
        with self.assertRaisesRegex(ValueError, "android dual-vantage gate mapping"):
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

    def test_unknown_evidence_source_is_rejected(self) -> None:
        broken = copy.deepcopy(self.policy)
        broken["gates"][0]["evidenceSource"] = "self-attested-results"
        with self.assertRaisesRegex(ValueError, "unknown evidenceSource"):
            gates.validate_policy(broken)

    def test_startup_barrier_evidence_contract_cannot_be_weakened(self) -> None:
        gate_id = "killswitch-tun-establish-native-ready"
        for mutation in ("missing-source", "missing-scope", "wrong-scope"):
            with self.subTest(mutation=mutation):
                broken = copy.deepcopy(self.policy)
                startup_gate = next(
                    gate for gate in broken["gates"] if gate["id"] == gate_id
                )
                if mutation == "missing-source":
                    startup_gate.pop("evidenceSource")
                elif mutation == "missing-scope":
                    startup_gate.pop("appliesTo")
                else:
                    startup_gate["appliesTo"] = ["fleet-profile-rollout"]

                with self.assertRaisesRegex(ValueError, "startup barrier gate"):
                    gates.validate_policy(broken)

    def test_cli_rejects_weakened_startup_barrier_policy(self) -> None:
        gate_id = "killswitch-tun-establish-native-ready"
        for mutation in ("missing-source", "missing-scope", "wrong-scope"):
            with (
                self.subTest(mutation=mutation),
                tempfile.TemporaryDirectory() as root,
            ):
                broken = copy.deepcopy(self.policy)
                startup_gate = next(
                    gate for gate in broken["gates"] if gate["id"] == gate_id
                )
                if mutation == "missing-source":
                    startup_gate.pop("evidenceSource")
                elif mutation == "missing-scope":
                    startup_gate.pop("appliesTo")
                else:
                    startup_gate["appliesTo"] = ["fleet-profile-rollout"]
                policy_path = Path(root) / "policy.json"
                results_path = Path(root) / "results.json"
                policy_path.write_text(json.dumps(broken), encoding="utf-8")
                results_path.write_text(
                    json.dumps(self.results_document()), encoding="utf-8"
                )

                with self.assertRaisesRegex(ValueError, "startup barrier gate"):
                    gates.main(
                        [
                            "--policy",
                            str(policy_path),
                            "--results",
                            str(results_path),
                            "--applies-to",
                            "android-client-release",
                            "--expected-source-sha",
                            "a" * 40,
                        ]
                    )

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
        def fail_closed_document(source_sha: str = "a" * 40) -> dict:
            document = self.results_document(source_sha=source_sha)
            ordinary_ids = set(gates.android_ordinary_results.ORDINARY_GATE_IDS)
            for gate_id in ordinary_ids & set(document["gateResults"]):
                document["gateResults"][gate_id] = {
                    "reason": "source-owned verifier unavailable",
                    "state": "FAIL",
                }
            return document

        validated = gates.validate_results_document(
            self.policy,
            fail_closed_document(),
            expected_source_sha="a" * 40,
            applies_to="android-client-release",
        )
        self.assertEqual(validated["sourceSha"], "a" * 40)

        with self.assertRaisesRegex(ValueError, "sourceSha"):
            gates.validate_results_document(
                self.policy,
                fail_closed_document(source_sha="b" * 40),
                expected_source_sha="a" * 40,
                applies_to="android-client-release",
            )

        wrong_scope = fail_closed_document()
        wrong_scope["appliesTo"] = "fleet-profile-rollout"
        with self.assertRaisesRegex(ValueError, "appliesTo"):
            gates.validate_results_document(
                self.policy,
                wrong_scope,
                expected_source_sha="a" * 40,
                applies_to="android-client-release",
            )

    def test_android_results_reject_partial_ordinary_inventory_with_unknown_extra(
        self,
    ) -> None:
        document = self.ordinary_results_document()
        document["gateResults"].pop("ipv4only-no-direct-ipv6")
        document["gateResults"]["not-a-policy-gate"] = "FAIL"

        with self.assertRaisesRegex(ValueError, "exactly cover"):
            gates.validate_results_document(
                self.policy,
                document,
                expected_source_sha="a" * 40,
                applies_to="android-client-release",
            )

    def test_android_results_reject_partial_ordinary_inventory_with_dual_extra(
        self,
    ) -> None:
        document = self.ordinary_results_document()
        document["gateResults"].pop("ipv4only-no-direct-ipv6")
        document["gateResults"]["dns-virtual-vpn-resolver"] = "PASS"

        with self.assertRaisesRegex(ValueError, "exactly cover"):
            gates.validate_results_document(
                self.policy,
                document,
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
        self.assertTrue(
            any("missing string state" in item for item in evaluation["violations"])
        )
        self.assertTrue(
            any("unknown gate id" in item for item in evaluation["violations"])
        )

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
            results_path.write_text(
                json.dumps({"gateResults": results}), encoding="utf-8"
            )
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(gates.main(["--results", str(results_path)]), 1)

    def test_main_requires_expected_source_sha_with_results(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            results_path = Path(directory) / "results.json"
            results_path.write_text(
                json.dumps(self.results_document()), encoding="utf-8"
            )
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

    def test_main_rejects_self_attested_startup_barrier_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            results_path = Path(directory) / "results.json"
            results_path.write_text(
                json.dumps(self.results_document()), encoding="utf-8"
            )
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
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
                    1,
                )
            self.assertIn("require --evidence-manifest", stderr.getvalue())

    def test_main_rejects_self_attested_ordinary_pass_with_dual_vantage_evidence(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = self.evidence_bundle(root)
            results_path = self.write_ordinary_results(root)
            with self.assertRaisesRegex(
                ValueError, "SOURCE_OWNED_VERIFIER_UNAVAILABLE"
            ):
                gates.main(
                    [
                        "--results",
                        str(results_path),
                        "--evidence-manifest",
                        str(manifest_path),
                        "--applies-to",
                        "android-client-release",
                        "--expected-source-sha",
                        "a" * 40,
                    ]
                )

    def test_release_checker_rejects_unapproved_manifest_producers(self) -> None:
        root = Path(self.temp.name) / "unapproved-release"
        root.mkdir()
        manifest_path = self.evidence_bundle(root)
        results_path = self.write_ordinary_results(root)
        empty_policy = {
            "version": "network_evidence_producers_v2",
            "clientCollectorSha256": [],
            "observerCollectorSha256": [],
            "workloadSha256": [],
            "clientArtifactSha256": [],
            "testArtifactSha256": [],
        }
        gates.network_evidence_manifest.write_canonical_json(
            gates.network_evidence_manifest.PRODUCER_POLICY_PATH, empty_policy
        )
        manifest = gates.network_evidence_manifest.load_json_bytes(manifest_path)[0]
        manifest["provenance"]["producerPolicySha256"] = (
            gates.network_evidence_manifest.sha256_bytes(
                gates.network_evidence_manifest.PRODUCER_POLICY_PATH.read_bytes()
            )
        )
        gates.network_evidence_manifest.write_canonical_json(manifest_path, manifest)
        with self.assertRaisesRegex(ValueError, "digest is not approved"):
            gates.main(
                [
                    "--results",
                    str(results_path),
                    "--evidence-manifest",
                    str(manifest_path),
                    "--applies-to",
                    "android-client-release",
                    "--expected-source-sha",
                    "a" * 40,
                ]
            )

    def test_main_accepts_all_19_fleet_gates_from_ordinary_results(self) -> None:
        fleet_ids = gates.applicable_gate_ids(
            self.policy, applies_to="fleet-profile-rollout"
        )
        document = {
            "version": gates.EXPECTED_RESULTS_VERSION,
            "sourceSha": "a" * 40,
            "appliesTo": "fleet-profile-rollout",
            "gateResults": {gate_id: "PASS" for gate_id in fleet_ids},
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.json"
            path.write_text(json.dumps(document), encoding="utf-8")
            self.assertEqual(
                gates.main(
                    [
                        "--results",
                        str(path),
                        "--applies-to",
                        "fleet-profile-rollout",
                        "--expected-source-sha",
                        "a" * 40,
                    ]
                ),
                0,
            )

    def test_main_rejects_incomplete_combined_gate_union(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = self.evidence_bundle(root)
            results = self.ordinary_results_document()
            results["gateResults"] = {
                gate_id: {
                    "reason": "source-owned verifier unavailable",
                    "state": "FAIL",
                }
                for gate_id in results["gateResults"]
            }
            results["gateResults"].pop("ipv4only-no-direct-ipv6")
            results_path = root / "results.json"
            results_path.write_text(json.dumps(results), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "exactly cover"):
                gates.main(
                    [
                        "--results",
                        str(results_path),
                        "--evidence-manifest",
                        str(manifest_path),
                        "--applies-to",
                        "android-client-release",
                        "--expected-source-sha",
                        "a" * 40,
                    ]
                )

    def test_main_rejects_noncanonical_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = self.evidence_bundle(root)
            results_path = self.write_ordinary_results(root)
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "not canonical JSON"):
                gates.main(
                    [
                        "--results",
                        str(results_path),
                        "--evidence-manifest",
                        str(manifest_path),
                        "--applies-to",
                        "android-client-release",
                        "--expected-source-sha",
                        "a" * 40,
                    ]
                )

    def test_main_rejects_derived_capture_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = self.evidence_bundle(
                root, failed_gate="killswitch-tun-establish-native-ready"
            )
            results_path = self.write_ordinary_failure_results(root)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(
                    gates.main(
                        [
                            "--results",
                            str(results_path),
                            "--evidence-manifest",
                            str(manifest_path),
                            "--applies-to",
                            "android-client-release",
                            "--expected-source-sha",
                            "a" * 40,
                        ]
                    ),
                    1,
                )

    def test_main_rejects_inconclusive_capture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest_path = self.evidence_bundle(
                root,
                capture_error_gate="killswitch-tun-establish-native-ready",
            )
            results_path = self.write_ordinary_failure_results(root)
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(
                    gates.main(
                        [
                            "--results",
                            str(results_path),
                            "--evidence-manifest",
                            str(manifest_path),
                            "--applies-to",
                            "android-client-release",
                            "--expected-source-sha",
                            "a" * 40,
                        ]
                    ),
                    1,
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
            root = Path(directory)
            results_path = root / "results.json"
            results_path.write_text("{not-json", encoding="utf-8")
            manifest_path = self.evidence_bundle(root)
            completed = subprocess.run(
                [
                    sys.executable,
                    str(
                        Path(__file__).resolve().parents[1]
                        / "ci/check_dns_ipv6_killswitch_gates.py"
                    ),
                    "--results",
                    str(results_path),
                    "--evidence-manifest",
                    str(manifest_path),
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
        evidence = (
            root / ".github/workflows/dns-ipv6-killswitch-evidence.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("check_dns_ipv6_killswitch_gates.py --policy-only", ci)
        self.assertIn("actions/download-artifact@", release)
        self.assertIn("dns-ipv6-killswitch-release-evidence", release)
        self.assertIn("--evidence-manifest", release)
        self.assertIn('--results "$results_path"', release)
        self.assertIn("RIPDPI_DNS_IPV6_KILLSWITCH_RESULTS", release)
        self.assertIn("--applies-to android-client-release", release)
        self.assertIn('--expected-source-sha "$GITHUB_SHA"', release)
        self.assertIn("actions/runs/", release)
        self.assertIn("--expected-evidence-run-id", release)
        self.assertIn("run-id:", release)

        self.assertIn("ripdpi-network-evidence", evidence)
        self.assertIn("physical-android", evidence)
        self.assertIn("run-dual-vantage-network-evidence.sh", evidence)
        self.assertIn(":app:assembleGithubFullDebugAndroidTest", evidence)
        self.assertIn('--test-artifact "$TEST_ARTIFACT_PATH"', evidence)
        self.assertIn("com.poyka.ripdpi.test", evidence)
        self.assertIn("TEST_ARTIFACT_SHA256", evidence)
        self.assertIn('--expected-source-sha "$GITHUB_SHA"', evidence)
        self.assertNotIn("--results", evidence)
        self.assertIn("network_evidence_manifest.py validate", evidence)
        self.assertIn("--require-pass", evidence)
        self.assertNotIn("check_dns_ipv6_killswitch_gates.py", evidence)
        self.assertIn("actions/upload-artifact@", evidence)
        self.assertIn("dns-ipv6-killswitch-release-evidence", evidence)
        self.assertIn(
            "EVIDENCE_OUTPUT_NAME: dns-ipv6-killswitch-release-evidence-"
            "${{ github.run_id }}-${{ github.run_attempt }}",
            evidence,
        )
        self.assertEqual(evidence.count("EVIDENCE_OUTPUT_NAME"), 5)
        self.assertEqual(evidence.count('"$RUNNER_TEMP/$EVIDENCE_OUTPUT_NAME'), 3)
        self.assertIn(
            "path: ${{ runner.temp }}/${{ env.EVIDENCE_OUTPUT_NAME }}", evidence
        )
        self.assertIn("if: success()", evidence)
        self.assertNotIn("if: always()", evidence)
        self.assertNotIn("$RUNNER_TEMP/dns-ipv6-killswitch-release-evidence", evidence)
        self.assertIn("scripts.tests.test_network_evidence_manifest", ci)
        self.assertIn("scripts.tests.test_network_evidence_pcap_oracle", ci)
        self.assertIn("test-dual-vantage-network-evidence.sh", ci)
        self.assertIn("jsonschema==4.26.0", ci)


if __name__ == "__main__":
    unittest.main()
