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
    exact_one = {counter: 1 for counter in MODULE.EXACT_ONE_COUNTERS}
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
        "mapDns": {
            "addressFamily": "ipv4",
            "syntheticEndpoint": "198.18.0.53:53",
            "armedAllowlistVerified": True,
            "armedControlFailureKind": "TIMEOUT",
            "armedControlFailureStage": "receive",
            "armedControlErrno": 110,
            "allowedRoundTrips": 1,
            "allowedExactAnswerVerified": True,
            "allowedResolverEvents": 1,
            "allowedDnsQueriesDelta": 1,
            "deniedBlockedAttempts": 1,
            "deniedFailureKind": "TIMEOUT",
            "deniedFailureStage": "receive",
            "deniedErrno": 110,
            "deniedResolverEvents": 0,
            "deniedDnsQueriesDelta": 0,
        },
        "families": [
            {
                "family": family,
                "icmpProtocol": "icmpv6" if family == "ipv6" else "icmpv4",
                "sourceFamilyVerified": True,
                "deniedTcpErrno": 110,
                "deniedTcpFailureKind": "TIMEOUT",
                "deniedTcpFailureStage": "connect",
                "deniedUdpErrno": 110,
                "deniedUdpFailureKind": "TIMEOUT",
                "deniedUdpFailureStage": "receive",
                "allowedUidIcmpErrno": 110,
                "allowedUidIcmpFailureKind": "TIMEOUT",
                "allowedUidIcmpFailureStage": "receive",
                "deniedUidIcmpErrno": 110,
                "deniedUidIcmpFailureKind": "TIMEOUT",
                "deniedUidIcmpFailureStage": "receive",
                **positive,
                **exact_one,
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

    def test_rejects_inconsistent_tcp_reset_errno(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedTcpFailureKind"] = "CONNECTION_RESET"
        evidence["families"][0]["deniedTcpErrno"] = 110
        with self.assertRaisesRegex(ValueError, "reset kind/errno pair is inconsistent"):
            self.validate(evidence)

    def test_rejects_inconsistent_tcp_timeout_errno(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedTcpErrno"] = 104
        with self.assertRaisesRegex(ValueError, "TCP timeout kind/errno pair is inconsistent"):
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

    def test_rejects_inconsistent_udp_timeout_errno(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedUdpErrno"] = 104
        with self.assertRaisesRegex(ValueError, "UDP timeout kind/errno pair is inconsistent"):
            self.validate(evidence)

    def test_accepts_unreachable_connect_as_mapdns_block_outcome(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["deniedFailureKind"] = "ERRNO"
        evidence["mapDns"]["deniedFailureStage"] = "connect"
        evidence["mapDns"]["deniedErrno"] = 101
        self.validate(evidence)

    def test_rejects_inconsistent_mapdns_timeout_errno(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["deniedErrno"] = 104
        with self.assertRaisesRegex(ValueError, "mapDns timeout kind/errno pair is inconsistent"):
            self.validate(evidence)

    def test_rejects_mapdns_duplicated_into_family_evidence(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["allowedMapDnsRoundTrips"] = 1
        with self.assertRaisesRegex(ValueError, "fields mismatch"):
            self.validate(evidence)

    def test_rejects_unarmed_mapdns_allowlist(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["armedAllowlistVerified"] = False
        with self.assertRaisesRegex(ValueError, "allowlist was not proven armed"):
            self.validate(evidence)

    def test_rejects_einval_as_armed_allowlist_proof(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"].update(
            armedControlFailureKind="ERRNO",
            armedControlFailureStage="connect",
            armedControlErrno=22,
        )
        with self.assertRaisesRegex(ValueError, "not an unreachable connect outcome"):
            self.validate(evidence)

    def test_rejects_eio_send_as_armed_allowlist_proof(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"].update(
            armedControlFailureKind="ERRNO",
            armedControlFailureStage="send",
            armedControlErrno=5,
        )
        with self.assertRaisesRegex(ValueError, "not an unreachable connect outcome"):
            self.validate(evidence)

    def test_rejects_pre_network_armed_allowlist_failure(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["armedControlFailureStage"] = "bind"
        with self.assertRaisesRegex(ValueError, "did not fail at a network stage"):
            self.validate(evidence)

    def test_rejects_inexact_mapdns_answer(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["allowedExactAnswerVerified"] = False
        with self.assertRaisesRegex(ValueError, "exact fixture answer"):
            self.validate(evidence)

    def test_rejects_duplicate_mapdns_resolver_event(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["allowedResolverEvents"] = 2
        with self.assertRaisesRegex(ValueError, "must equal one"):
            self.validate(evidence)

    def test_rejects_boolean_mapdns_counter(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["allowedDnsQueriesDelta"] = True
        with self.assertRaisesRegex(ValueError, "must equal one"):
            self.validate(evidence)

    def test_rejects_icmp_einval_as_block_proof(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0].update(
            allowedUidIcmpFailureKind="ERRNO",
            allowedUidIcmpFailureStage="connect",
            allowedUidIcmpErrno=22,
        )
        with self.assertRaisesRegex(ValueError, "not an unreachable connect outcome"):
            self.validate(evidence)

    def test_rejects_icmp_timeout_before_receive(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["deniedUidIcmpFailureStage"] = "send"
        with self.assertRaisesRegex(ValueError, "timeout kind/stage/errno is inconsistent"):
            self.validate(evidence)

    def test_rejects_icmp_timeout_with_reset_errno(self) -> None:
        evidence = valid_evidence()
        evidence["families"][1]["allowedUidIcmpErrno"] = 104
        with self.assertRaisesRegex(ValueError, "timeout kind/stage/errno is inconsistent"):
            self.validate(evidence)

    def test_rejects_positive_icmp_fixture_receipt_for_denied_uid(self) -> None:
        evidence = valid_evidence()
        evidence["families"][1]["deniedUidIcmpFixtureEvents"] = 1
        with self.assertRaisesRegex(ValueError, "must be zero"):
            self.validate(evidence)

    def test_rejects_duplicate_direct_icmp_fixture_receipt(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["directIcmpFixtureEvents"] = 2
        with self.assertRaisesRegex(ValueError, "must equal one"):
            self.validate(evidence)

    def test_rejects_zero_direct_icmp_fixture_receipt(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["directIcmpFixtureEvents"] = 0
        with self.assertRaisesRegex(ValueError, "must equal one"):
            self.validate(evidence)

    def test_rejects_missing_icmp_fact(self) -> None:
        evidence = valid_evidence()
        del evidence["families"][0]["livenessIcmpEchoReplies"]
        with self.assertRaisesRegex(ValueError, "fields mismatch"):
            self.validate(evidence)

    def test_rejects_wrong_family_icmp_protocol(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["icmpProtocol"] = "icmpv6"
        with self.assertRaisesRegex(ValueError, "does not match the address family"):
            self.validate(evidence)

    def test_rejects_aggregate_tun_traffic_as_icmp_ingress_proof(self) -> None:
        evidence = valid_evidence()
        evidence["families"][0]["allowedUidIcmpAttemptWindowTxPacketDelta"] = 1
        del evidence["families"][0]["allowedUidIcmpIngressPackets"]
        with self.assertRaisesRegex(ValueError, "fields mismatch"):
            self.validate(evidence)

    def test_rejects_denied_mapdns_resolver_delta(self) -> None:
        evidence = valid_evidence()
        evidence["mapDns"]["deniedDnsQueriesDelta"] = 1
        with self.assertRaisesRegex(ValueError, "must equal zero"):
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
