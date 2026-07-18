#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts/ci/check_so_bindtodevice_evidence.py"
SPEC = importlib.util.spec_from_file_location("check_so_bindtodevice_evidence", MODULE_PATH)
assert SPEC and SPEC.loader
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)


class SoBindToDeviceEvidenceTest(unittest.TestCase):
    sha = "a" * 40
    run_id = "1234"
    attempt = "2"

    def valid_manifest(self) -> dict:
        phases = []
        for phase_id in evidence.PHASES:
            family, contract, protocol = phase_id.split("_", 2)
            if contract == "direct":
                counts = (0, 0, 0, 0, 1)
            elif contract == "allowed":
                counts = (3, 2, 0, 1, 1)
            elif protocol == "tcp":
                counts = (3, 1, 1, 0, 0)
            else:
                counts = (1, 0, 0, 0, 0)
            inbound, outbound, resets, socks, peer = counts
            phases.append(
                {
                    "family": family,
                    "id": phase_id,
                    "outcome": "PASS",
                    "peerEvents": peer,
                    "protocol": protocol,
                    "socksEvents": socks,
                    "tunInboundPackets": inbound,
                    "tunOutboundPackets": outbound,
                    "tunOutboundResets": resets,
                }
            )
        return {
            "capabilities": {
                "ipv4": True,
                "ipv6": True,
                "realTun": True,
                "unprivilegedSoBindToDevice": True,
            },
            "cleanupVerified": True,
            "phases": phases,
            "provenance": {
                "sourceSha": self.sha,
                "testTarget": evidence.TARGET,
                "workflowRunAttempt": self.attempt,
                "workflowRunId": self.run_id,
            },
            "result": "PASS",
            "version": evidence.VERSION,
        }

    def validate(self, manifest: dict) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_bytes(evidence.canonical_bytes(manifest))
            return evidence.validate(
                path,
                expected_source_sha=self.sha,
                expected_run_id=self.run_id,
                expected_run_attempt=self.attempt,
            )

    def test_accepts_complete_physical_evidence(self) -> None:
        self.assertEqual(self.validate(self.valid_manifest())["result"], "PASS")

    def test_accepts_classified_infrastructure_gap(self) -> None:
        manifest = evidence.failure_manifest(
            result="INFRA_GAP",
            reason_code="TUN_UNAVAILABLE",
            cleanup_verified=False,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )

        self.assertEqual(self.validate(manifest), manifest)
        self.assertEqual(manifest["reasonCode"], "TUN_UNAVAILABLE")
        self.assertNotIn("capabilities", manifest)
        self.assertNotIn("phases", manifest)

    def test_accepts_classified_unsupported_ipv6_gap(self) -> None:
        manifest = evidence.failure_manifest(
            result="INFRA_GAP",
            reason_code="IPV6_UNAVAILABLE",
            cleanup_verified=False,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )

        self.assertEqual(self.validate(manifest), manifest)

    def test_accepts_classified_test_failure_after_cleanup(self) -> None:
        manifest = evidence.failure_manifest(
            result="TEST_FAILURE",
            reason_code="ADVERSARIAL_TEST_FAILED",
            cleanup_verified=True,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )

        self.assertEqual(self.validate(manifest), manifest)

    def test_rejects_reason_code_from_other_failure_class(self) -> None:
        manifest = evidence.failure_manifest(
            result="INFRA_GAP",
            reason_code="TUN_UNAVAILABLE",
            cleanup_verified=False,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )
        manifest["reasonCode"] = "ADVERSARIAL_TEST_FAILED"

        with self.assertRaisesRegex(ValueError, "reasonCode is invalid"):
            self.validate(manifest)

    def test_rejects_non_string_failure_discriminators(self) -> None:
        cases = (("result", []), ("reasonCode", {}))
        for field, value in cases:
            with self.subTest(field=field):
                manifest = evidence.failure_manifest(
                    result="INFRA_GAP",
                    reason_code="TUN_UNAVAILABLE",
                    cleanup_verified=False,
                    source_sha=self.sha,
                    run_id=self.run_id,
                    run_attempt=self.attempt,
                )
                manifest[field] = value
                with self.assertRaisesRegex(ValueError, "must be a string"):
                    self.validate(manifest)

    def test_rejects_cleanup_state_that_contradicts_reason(self) -> None:
        manifest = evidence.failure_manifest(
            result="TEST_FAILURE",
            reason_code="CLEANUP_FAILED",
            cleanup_verified=False,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )
        manifest["cleanupVerified"] = True

        with self.assertRaisesRegex(ValueError, "cleanupVerified is invalid"):
            self.validate(manifest)

    def test_failure_writer_is_canonical(self) -> None:
        manifest = evidence.failure_manifest(
            result="TEST_FAILURE",
            reason_code="CLEANUP_FAILED",
            cleanup_verified=False,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "nested" / "manifest.json"
            evidence.write_failure_manifest(path, manifest)

            self.assertEqual(path.read_bytes(), evidence.canonical_bytes(manifest))
            self.assertEqual(
                evidence.validate(
                    path,
                    expected_source_sha=self.sha,
                    expected_run_id=self.run_id,
                    expected_run_attempt=self.attempt,
                ),
                manifest,
            )

    def test_cli_rejects_valid_classified_failure(self) -> None:
        manifest = evidence.failure_manifest(
            result="INFRA_GAP",
            reason_code="ROOT_REQUIRED",
            cleanup_verified=False,
            source_sha=self.sha,
            run_id=self.run_id,
            run_attempt=self.attempt,
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            evidence.write_failure_manifest(path, manifest)

            completed = subprocess.run(
                [
                    sys.executable,
                    str(MODULE_PATH),
                    "--manifest",
                    str(path),
                    "--expected-source-sha",
                    self.sha,
                    "--expected-run-id",
                    self.run_id,
                    "--expected-run-attempt",
                    self.attempt,
                ],
                capture_output=True,
                check=False,
                text=True,
            )

            self.assertEqual(completed.returncode, 1)
            self.assertIn("INFRA_GAP/ROOT_REQUIRED", completed.stderr)

    def test_rejects_legacy_ambiguous_infrastructure_reasons(self) -> None:
        for reason_code in ("PRIVILEGE_MISSING", "TOOL_MISSING"):
            with self.subTest(reason_code=reason_code), self.assertRaisesRegex(ValueError, "reason code"):
                evidence.failure_manifest(
                    result="INFRA_GAP",
                    reason_code=reason_code,
                    cleanup_verified=False,
                    source_sha=self.sha,
                    run_id=self.run_id,
                    run_attempt=self.attempt,
                )

    def test_finalizer_classifies_prerequisite_and_build_failures(self) -> None:
        cases = (
            ("failure", "skipped", "skipped", "INFRA_GAP", "PREREQUISITE_SETUP_FAILED"),
            ("success", "failure", "skipped", "TEST_FAILURE", "BUILD_FAILED"),
        )
        for prerequisite, build, runtime, result, reason in cases:
            with self.subTest(result=result, reason=reason), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "manifest.json"
                manifest = evidence.finalize_workflow_evidence(
                    path,
                    prerequisite_outcome=prerequisite,
                    build_outcome=build,
                    runtime_outcome=runtime,
                    source_sha=self.sha,
                    run_id=self.run_id,
                    run_attempt=self.attempt,
                )

                self.assertEqual(manifest["result"], result)
                self.assertEqual(manifest["reasonCode"], reason)
                self.assertFalse(manifest["cleanupVerified"])
                self.assertEqual(path.read_bytes(), evidence.canonical_bytes(manifest))

    def test_finalizer_classifies_runtime_failure_without_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"

            manifest = evidence.finalize_workflow_evidence(
                path,
                prerequisite_outcome="success",
                build_outcome="success",
                runtime_outcome="failure",
                source_sha=self.sha,
                run_id=self.run_id,
                run_attempt=self.attempt,
            )

            self.assertEqual(manifest["result"], "TEST_FAILURE")
            self.assertEqual(manifest["reasonCode"], "RUNTIME_FAILED")
            self.assertFalse(manifest["cleanupVerified"])

    def test_finalizer_cannot_preserve_pass_from_failed_runtime_step(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_bytes(evidence.canonical_bytes(self.valid_manifest()))

            manifest = evidence.finalize_workflow_evidence(
                path,
                prerequisite_outcome="success",
                build_outcome="success",
                runtime_outcome="failure",
                source_sha=self.sha,
                run_id=self.run_id,
                run_attempt=self.attempt,
            )

            self.assertEqual(manifest["result"], "TEST_FAILURE")
            self.assertEqual(manifest["reasonCode"], "RUNTIME_FAILED")
            self.assertFalse(manifest["cleanupVerified"])

    def test_finalizer_cannot_preserve_pass_from_incomplete_runtime_step(self) -> None:
        for runtime_outcome in ("cancelled", "skipped"):
            with self.subTest(runtime_outcome=runtime_outcome), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "manifest.json"
                path.write_bytes(evidence.canonical_bytes(self.valid_manifest()))

                manifest = evidence.finalize_workflow_evidence(
                    path,
                    prerequisite_outcome="success",
                    build_outcome="success",
                    runtime_outcome=runtime_outcome,
                    source_sha=self.sha,
                    run_id=self.run_id,
                    run_attempt=self.attempt,
                )

                self.assertEqual(manifest["result"], "TEST_FAILURE")
                self.assertEqual(manifest["reasonCode"], "RUNTIME_FAILED")
                self.assertFalse(manifest["cleanupVerified"])

    def test_finalizer_preserves_specific_valid_runtime_failure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            failure = evidence.failure_manifest(
                result="TEST_FAILURE",
                reason_code="CLEANUP_FAILED",
                cleanup_verified=False,
                source_sha=self.sha,
                run_id=self.run_id,
                run_attempt=self.attempt,
            )
            path.write_bytes(evidence.canonical_bytes(failure))

            manifest = evidence.finalize_workflow_evidence(
                path,
                prerequisite_outcome="success",
                build_outcome="success",
                runtime_outcome="failure",
                source_sha=self.sha,
                run_id=self.run_id,
                run_attempt=self.attempt,
            )

            self.assertEqual(manifest, failure)

    def test_finalizer_replaces_malformed_evidence_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text('{"result":"PASS"}\n', encoding="utf-8")

            manifest = evidence.finalize_workflow_evidence(
                path,
                prerequisite_outcome="success",
                build_outcome="success",
                runtime_outcome="failure",
                source_sha=self.sha,
                run_id=self.run_id,
                run_attempt=self.attempt,
            )

            self.assertEqual(manifest["result"], "TEST_FAILURE")
            self.assertEqual(manifest["reasonCode"], "EVIDENCE_MALFORMED_UNVERIFIED")
            self.assertFalse(manifest["cleanupVerified"])

    def test_finalizer_replaces_non_string_failure_discriminators(self) -> None:
        for field, value in (("result", []), ("reasonCode", {})):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "manifest.json"
                malformed = evidence.failure_manifest(
                    result="INFRA_GAP",
                    reason_code="TUN_UNAVAILABLE",
                    cleanup_verified=False,
                    source_sha=self.sha,
                    run_id=self.run_id,
                    run_attempt=self.attempt,
                )
                malformed[field] = value
                path.write_bytes(evidence.canonical_bytes(malformed))

                manifest = evidence.finalize_workflow_evidence(
                    path,
                    prerequisite_outcome="success",
                    build_outcome="success",
                    runtime_outcome="failure",
                    source_sha=self.sha,
                    run_id=self.run_id,
                    run_attempt=self.attempt,
                )

                self.assertEqual(manifest["result"], "TEST_FAILURE")
                self.assertEqual(manifest["reasonCode"], "EVIDENCE_MALFORMED_UNVERIFIED")

    def test_rejects_missing_ipv6_phase(self) -> None:
        manifest = self.valid_manifest()
        manifest["phases"].pop()
        with self.assertRaisesRegex(ValueError, "phase ids/order"):
            self.validate(manifest)

    def test_rejects_generic_tcp_failure_without_reset(self) -> None:
        manifest = self.valid_manifest()
        phase = next(phase for phase in manifest["phases"] if phase["id"] == "ipv4_denied_tcp")
        phase["tunOutboundResets"] = 0
        with self.assertRaisesRegex(ValueError, "denied TCP"):
            self.validate(manifest)

    def test_rejects_denied_udp_upstream_delivery(self) -> None:
        manifest = self.valid_manifest()
        phase = next(phase for phase in manifest["phases"] if phase["id"] == "ipv6_denied_udp")
        phase["peerEvents"] = 1
        with self.assertRaisesRegex(ValueError, "denied UDP"):
            self.validate(manifest)

    def test_rejects_false_direct_control(self) -> None:
        manifest = self.valid_manifest()
        phase = next(phase for phase in manifest["phases"] if phase["id"] == "ipv4_direct_tcp")
        phase["tunInboundPackets"] = 1
        with self.assertRaisesRegex(ValueError, "direct-path"):
            self.validate(manifest)

    def test_rejects_incomplete_cleanup(self) -> None:
        manifest = self.valid_manifest()
        manifest["cleanupVerified"] = False
        with self.assertRaisesRegex(ValueError, "cleanupVerified"):
            self.validate(manifest)

    def test_rejects_extra_or_noncanonical_content(self) -> None:
        manifest = copy.deepcopy(self.valid_manifest())
        manifest["rawUid"] = 65534
        with self.assertRaisesRegex(ValueError, "manifest fields"):
            self.validate(manifest)

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(self.valid_manifest(), indent=2), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "canonical"):
                evidence.validate(
                    path,
                    expected_source_sha=self.sha,
                    expected_run_id=self.run_id,
                    expected_run_attempt=self.attempt,
                )


if __name__ == "__main__":
    unittest.main()
