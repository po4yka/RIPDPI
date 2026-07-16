#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
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
