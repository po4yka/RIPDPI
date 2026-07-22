#!/usr/bin/env python3
"""Regression tests for Android SO_BINDTODEVICE physical evidence."""

from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
NOW_EPOCH_MS = 2_000_000_000_000
RUN_ID = "a" * 32
SOURCE_SHA = "b" * 40
APP_APK_SHA256 = "c" * 64
TEST_APK_SHA256 = "d" * 64
MODULE_PATH = ROOT / "scripts/ci/check_android_so_bind_physical_evidence.py"
SPEC = importlib.util.spec_from_file_location(
    "check_android_so_bind_physical_evidence", MODULE_PATH
)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def valid_evidence() -> dict[str, object]:
    positive = {counter: 1 for counter in MODULE.POSITIVE_COUNTERS}
    return {
        "version": MODULE.VERSION,
        "status": "PASS",
        "profile": MODULE.PROFILE,
        "runId": RUN_ID,
        "sourceSha": SOURCE_SHA,
        "appApkSha256": APP_APK_SHA256,
        "testApkSha256": TEST_APK_SHA256,
        "startedAtEpochMs": NOW_EPOCH_MS - 1_000,
        "finishedAtEpochMs": NOW_EPOCH_MS,
        "deviceManufacturer": "Google",
        "deviceCodename": "panther",
        "apiLevel": 37,
        "kernelFamily": "6.1",
        "realTun": True,
        "tunPacketPathObserved": True,
        "families": [
            {
                "family": family,
                "sourceFamilyVerified": True,
                "deniedTcpErrno": 110,
                "deniedTcpFailureKind": "TIMEOUT",
                "deniedTcpFailureStage": "connect",
                "deniedUdpErrno": 110,
                "deniedUdpFailureKind": "TIMEOUT",
                "deniedUdpFailureStage": "receive",
                **positive,
                **{counter: 0 for counter in MODULE.ZERO_COUNTERS},
            }
            for family in MODULE.FAMILIES
        ],
    }


class AndroidSoBindPhysicalEvidenceTest(unittest.TestCase):
    def validate(self, evidence: dict[str, object], **expected: object) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            MODULE.validate(path, now_epoch_ms=NOW_EPOCH_MS, **expected)

    def test_accepts_complete_ipv4_ipv6_evidence(self) -> None:
        self.validate(valid_evidence())

    def test_rejects_missing_ipv6_family(self) -> None:
        evidence = valid_evidence()
        evidence["families"] = evidence["families"][:1]
        with self.assertRaisesRegex(ValueError, "exactly IPv4 and IPv6"):
            self.validate(evidence)

    def test_rejects_positive_denied_fixture_counter(self) -> None:
        evidence = valid_evidence()
        evidence["families"][1]["deniedUdpFixtureEvents"] = 1
        with self.assertRaisesRegex(ValueError, "must be zero"):
            self.validate(evidence)

    def test_rejects_zero_round_trip_counter(self) -> None:
        evidence = valid_evidence()
        evidence["families"][1]["allowedTcpRoundTrips"] = 0
        with self.assertRaisesRegex(ValueError, "positive integer"):
            self.validate(evidence)

    def test_accepts_connection_reset_as_tcp_block_outcome(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedTcpFailureKind"] = "CONNECTION_RESET"
        evidence["families"][0]["deniedTcpErrno"] = 104
        self.validate(evidence)

    def test_accepts_unreachable_connect_as_tcp_block_outcome(self) -> None:
        evidence = valid_evidence()
        evidence["families"][1]["deniedTcpFailureKind"] = "ERRNO"
        evidence["families"][1]["deniedTcpErrno"] = 101
        self.validate(evidence)

    def test_rejects_non_blocking_tcp_failure_kind(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedTcpFailureKind"] = "IO_ERROR"
        with self.assertRaisesRegex(ValueError, "not a blocked outcome"):
            self.validate(evidence)

    def test_rejects_generic_errno_outside_unreachable_connect(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedTcpFailureKind"] = "ERRNO"
        evidence["families"][0]["deniedTcpErrno"] = 5
        with self.assertRaisesRegex(ValueError, "not an unreachable connect outcome"):
            self.validate(evidence)

    def test_rejects_tcp_failure_before_network_stage(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedTcpFailureStage"] = "bind"
        with self.assertRaisesRegex(ValueError, "not a network stage"):
            self.validate(evidence)

    def test_accepts_unreachable_connect_as_udp_block_outcome(self) -> None:
        evidence = valid_evidence()
        evidence["families"][1]["deniedUdpFailureKind"] = "ERRNO"
        evidence["families"][1]["deniedUdpFailureStage"] = "connect"
        evidence["families"][1]["deniedUdpErrno"] = 101
        self.validate(evidence)

    def test_rejects_udp_generic_errno_outside_unreachable_connect(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedUdpFailureKind"] = "ERRNO"
        evidence["families"][0]["deniedUdpFailureStage"] = "connect"
        evidence["families"][0]["deniedUdpErrno"] = 5
        with self.assertRaisesRegex(ValueError, "not an unreachable connect outcome"):
            self.validate(evidence)

    def test_rejects_obsolete_reset_only_schema(self) -> None:
        evidence = valid_evidence()
        evidence["version"] = "android_so_bind_physical_evidence_v1"
        for family in evidence["families"]:
            family["deniedTcpResets"] = family.pop("deniedTcpBlockedAttempts")
            family.pop("deniedTcpErrno")
            family.pop("deniedTcpFailureKind")
            family.pop("deniedTcpFailureStage")
            family["deniedUdpTimeouts"] = family.pop("deniedUdpBlockedAttempts")
            family.pop("deniedUdpErrno")
            family.pop("deniedUdpFailureKind")
            family.pop("deniedUdpFailureStage")
        with self.assertRaisesRegex(ValueError, "unsupported evidence version"):
            self.validate(evidence)

    def test_rejects_wrong_family_order(self) -> None:
        evidence = valid_evidence()
        evidence["families"] = list(reversed(evidence["families"]))
        with self.assertRaisesRegex(ValueError, "ordered ipv4 then ipv6"):
            self.validate(evidence)

    def test_rejects_unknown_fields(self) -> None:
        evidence = copy.deepcopy(valid_evidence())
        evidence["deviceSerial"] = "sensitive"
        with self.assertRaisesRegex(ValueError, "fields mismatch"):
            self.validate(evidence)

    def test_rejects_fabricated_current_run_id(self) -> None:
        with self.assertRaisesRegex(ValueError, "runId does not match the current run"):
            self.validate(valid_evidence(), expected_run_id="e" * 32)

    def test_rejects_mismatched_source_and_apk_digests(self) -> None:
        expected = {
            "expected_source_sha": "e" * 40,
            "expected_app_apk_sha256": APP_APK_SHA256,
            "expected_test_apk_sha256": TEST_APK_SHA256,
        }
        with self.assertRaisesRegex(
            ValueError, "sourceSha does not match the current run"
        ):
            self.validate(valid_evidence(), **expected)

    def test_rejects_stale_evidence(self) -> None:
        evidence = valid_evidence()
        evidence["startedAtEpochMs"] = NOW_EPOCH_MS - MODULE.MAX_EVIDENCE_AGE_MS - 2_000
        evidence["finishedAtEpochMs"] = (
            NOW_EPOCH_MS - MODULE.MAX_EVIDENCE_AGE_MS - 1_000
        )
        with self.assertRaisesRegex(ValueError, "stale or from the future"):
            self.validate(evidence)

    def test_rejects_excessive_capture_window(self) -> None:
        evidence = valid_evidence()
        evidence["startedAtEpochMs"] = NOW_EPOCH_MS - MODULE.MAX_RUN_DURATION_MS - 1
        with self.assertRaisesRegex(ValueError, "exceeds five minutes"):
            self.validate(evidence)

    def test_rejects_future_evidence(self) -> None:
        evidence = valid_evidence()
        evidence["startedAtEpochMs"] = NOW_EPOCH_MS + 60_001
        evidence["finishedAtEpochMs"] = NOW_EPOCH_MS + 60_001
        with self.assertRaisesRegex(ValueError, "stale or from the future"):
            self.validate(evidence)

    def test_rejects_malformed_provenance_digest(self) -> None:
        evidence = valid_evidence()
        evidence["appApkSha256"] = "not-a-digest"
        with self.assertRaisesRegex(ValueError, "lowercase SHA-256"):
            self.validate(evidence)

    def test_rejects_unqualified_device_facts(self) -> None:
        evidence = valid_evidence()
        evidence["deviceCodename"] = "generic"
        with self.assertRaisesRegex(ValueError, "device facts"):
            self.validate(evidence)


if __name__ == "__main__":
    unittest.main()
