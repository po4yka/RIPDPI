#!/usr/bin/env python3
"""Regression tests for Android SO_BINDTODEVICE physical evidence."""

from __future__ import annotations

import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
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
sys.modules[SPEC.name] = MODULE
OBSERVER_SPEC = importlib.util.spec_from_file_location("capture_android_so_bind_sockets", ROOT / "scripts/ci/capture_android_so_bind_sockets.py")
assert OBSERVER_SPEC and OBSERVER_SPEC.loader
OBSERVER = importlib.util.module_from_spec(OBSERVER_SPEC)
OBSERVER_SPEC.loader.exec_module(OBSERVER)


class FakeSocketCaptureAdb:
    def __init__(self, stop: Path, phases: list[str], *, denied: bool = False) -> None:
        self.stop = stop
        self.phases = iter(phases)
        self.last_phase = ""
        self.denied = denied
        self.acknowledgements: list[str] = []

    def run(self, command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
        header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        if command[-2:] == ["cat", OBSERVER.WINDOW]:
            phase = next(self.phases, self.last_phase)
            self.last_phase = phase
            if phase == "done":
                self.stop.touch()
            data = json.dumps({"runId": RUN_ID, "family": "ipv4", "phase": phase, "uid": 10001,
                               "host": "10.0.0.2", "controlPort": 45057, "deniedPort": 45058})
        elif command[-2:] == ["cat", "/proc/net/tcp"]:
            row = "0: 0100007F:A001 0200000A:B001 01 00000000:00000000 00:00000000 00000000 10001 0 42\n"
            data = header + row
            if self.denied:
                return subprocess.CompletedProcess(command, 1, "", "private permission error")
        elif command[-2:] == ["cat", "/proc/net/tcp6"]:
            data = header
        else:
            self.acknowledgements.append(kwargs.get("input", ""))
            data = ""
        return subprocess.CompletedProcess(command, 0, data, "")


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
        "qualification": {"unprivilegedBindToDevice": "supported", "uidPolicyEligible": True, "uidPolicyArmed": True},
        "legacy": None,
        "socketTable": [{"family": family, "liveSamples": 3, "minimumPositiveControlRows": 1, "deniedRemoteRows": 0, "synchronized": True} for family in MODULE.FAMILIES],
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
    def test_physical_build_keeps_gate_without_ambient_cargo_jobs(self) -> None:
        runner = (ROOT / "scripts/ci/run-android-so-bind-physical-e2e.sh").read_text()
        build_command = runner.split('build-gate -- ', 1)[1].split(
            ' || fail "source-bound physical APK build failed"', 1
        )[0]
        with tempfile.TemporaryDirectory() as directory:
            gradle = Path(directory) / "gradle"
            gradle.write_text(
                '#!/bin/sh\n'
                '[ "$BUILD_GATE_HELD" = 1 ] || exit 41\n'
                '[ "${CARGO_BUILD_JOBS+x}" != x ] || exit 42\n'
                'printf "%s\\n" "$@"\n'
            )
            gradle.chmod(0o700)
            result = subprocess.run(
                ["bash", "-c", '''
build-gate() {
    shift
    BUILD_GATE_HELD=1 CARGO_BUILD_JOBS=3 "$@"
}
gradle_bin="$1"
source_root="$2"
build-gate -- ''' + build_command, "physical-build-test", str(gradle), directory],
                capture_output=True, text=True, check=False,
            )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(":app:assembleGithubFullDebug", result.stdout.splitlines())
        self.assertIn(":app:assembleGithubFullDebugAndroidTest", result.stdout.splitlines())
        self.assertIn("-Pripdpi.nativeCpuBudget=2", result.stdout.splitlines())
        self.assertIn("-Pripdpi.nativeAbiParallelism=1", result.stdout.splitlines())

    def validate(self, evidence: dict[str, object], **expected: object) -> str:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(evidence), encoding="utf-8")
            return MODULE.validate(path, now_epoch_ms=NOW_EPOCH_MS, **expected)

    def test_accepts_complete_ipv4_ipv6_evidence(self) -> None:
        self.assertEqual("armed_dual_stack", self.validate(valid_evidence()))

    def test_accepts_pre57_backport_with_real_supported_capability(self) -> None:
        evidence = valid_evidence()
        evidence.update(profile="physical_kernel_lt57", kernelFamily="4.19", apiLevel=30)
        evidence["qualification"] = {"unprivilegedBindToDevice": "supported", "uidPolicyEligible": True, "uidPolicyArmed": True}
        self.validate(evidence)

    def test_accepts_pre57_permission_denied_only_with_vpn_liveness(self) -> None:
        evidence = valid_evidence()
        evidence.update(profile="physical_kernel_lt57", kernelFamily="4.19", apiLevel=30)
        evidence["qualification"] = {"unprivilegedBindToDevice": "permission_denied", "uidPolicyEligible": False, "uidPolicyArmed": False}
        evidence.update(mapDns=None, families=[], socketTable=[], legacy={
            "bindFailureKind": "ERRNO", "bindFailureStage": "bind", "bindErrno": 1,
            "distinctUidVerified": True, "vpnTcpRoundTrips": 1, "vpnUdpRoundTrips": 1,
            "vpnTcpFixtureEvents": 1, "vpnUdpFixtureEvents": 1,
        })
        self.assertEqual("legacy_ipv4", self.validate(evidence))

    def test_socket_sample_requires_visible_control_and_detects_leaked_tuple(self) -> None:
        header = "  sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        control = " 0: 0100007F:A001 0200000A:B001 01 00000000:00000000 00:00000000 00000000 10001 0 42\n"
        leak = " 1: 0100007F:A002 0200000A:B002 02 00000000:00000000 00:00000000 00000000 10001 0 43\n"
        with self.assertRaisesRegex(ValueError, "leaked"):
            MODULE.summarize_socket_sample(header + control + leak, header, uid=10001,
                host="10.0.0.2", control_port=45057, denied_port=45058)

    def test_rejects_armed_proof_without_live_host_socket_capture(self) -> None:
        evidence = valid_evidence()
        evidence.pop("socketTable", None)
        with self.assertRaisesRegex(ValueError, "socketTable"):
            self.validate(evidence)

    def test_rejects_malformed_socket_queue_even_with_visible_control(self) -> None:
        header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        row = "0: 0100007F:A001 0200000A:B001 01 invalid 00:00000000 00000000 10001 0 42\n"
        with self.assertRaisesRegex(ValueError, "malformed"):
            MODULE.summarize_socket_sample(header + row, header, uid=10001,
                host="10.0.0.2", control_port=45057, denied_port=45058)

    def test_rejects_integer_as_production_eligibility(self) -> None:
        evidence = valid_evidence()
        evidence["qualification"]["uidPolicyEligible"] = 1
        with self.assertRaisesRegex(ValueError, "boolean"):
            self.validate(evidence)

    def test_socket_visibility_rejects_empty_hidden_and_permission_denied_tables(self) -> None:
        header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        for tcp in ("", "cat: /proc/net/tcp: Permission denied\n", header):
            with self.subTest(tcp=tcp), self.assertRaises(ValueError):
                MODULE.summarize_socket_sample(tcp, header, uid=10001,
                    host="10.0.0.2", control_port=45057, denied_port=45058)

    def test_socket_visibility_decodes_ipv6_and_ipv4_mapped_rows(self) -> None:
        header = "sl local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        for host, remote in (("2001:db8::2", "B80D0120000000000000000002000000"),
                             ("10.0.0.2", "0000000000000000FFFF00000200000A")):
            row = f"0: 00000000000000000000000001000000:A001 {remote}:B001 01 00000000:00000000 00:00000000 00000000 10001 0 42\n"
            with self.subTest(host=host):
                self.assertEqual(MODULE.summarize_socket_sample(header, header + row, uid=10001,
                    host=host, control_port=45057, denied_port=45058),
                    {"positiveControlRows": 1, "deniedRemoteRows": 0})

    def test_socket_evidence_rejects_unsynchronized_invisible_and_leaking_capture(self) -> None:
        for field, value in (("synchronized", False), ("liveSamples", 2),
                             ("minimumPositiveControlRows", 0), ("deniedRemoteRows", 1)):
            evidence = valid_evidence()
            evidence["socketTable"][0][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "socketTable"):
                self.validate(evidence)

    def test_legacy_permission_denied_never_passes_without_positive_vpn_liveness(self) -> None:
        evidence = valid_evidence()
        evidence.update(profile="physical_kernel_lt57", kernelFamily="4.19", apiLevel=30,
            mapDns=None, families=[], socketTable=[], legacy={
            "bindFailureKind": "ERRNO", "bindFailureStage": "bind", "bindErrno": 1,
            "distinctUidVerified": True, "vpnTcpRoundTrips": 0, "vpnUdpRoundTrips": 1,
            "vpnTcpFixtureEvents": 1, "vpnUdpFixtureEvents": 1})
        evidence["qualification"] = {"unprivilegedBindToDevice": "permission_denied", "uidPolicyEligible": False, "uidPolicyArmed": False}
        with self.assertRaisesRegex(ValueError, "liveness"):
            self.validate(evidence)

    def test_observer_requires_ordered_live_handshake_before_redacted_result(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            stop = Path(directory) / "stop"
            fake = FakeSocketCaptureAdb(stop, ["ready", "active", "active", "active", "done"])
            with patch.object(OBSERVER.subprocess, "run", fake.run), patch.object(OBSERVER.time, "sleep"):
                result = OBSERVER.observe("adb", "private-serial", RUN_ID, stop, 2)
            self.assertEqual(result, [{"family": "ipv4", "liveSamples": 3, "minimumPositiveControlRows": 1,
                                      "deniedRemoteRows": 0, "synchronized": True}])
            self.assertEqual(fake.acknowledgements, [f"{RUN_ID}:ipv4:start", f"{RUN_ID}:ipv4:done"])

    def test_observer_rejects_late_capture_and_permission_denied_before_ack(self) -> None:
        for phases, denied in ((["done"], False), (["ready"], True)):
            with self.subTest(phases=phases, denied=denied), tempfile.TemporaryDirectory() as directory:
                stop = Path(directory) / "stop"
                fake = FakeSocketCaptureAdb(stop, phases, denied=denied)
                with patch.object(OBSERVER.subprocess, "run", fake.run), self.assertRaises(ValueError):
                    OBSERVER.observe("adb", "private-serial", RUN_ID, stop, 2)
                self.assertEqual(fake.acknowledgements, [])

    def test_observer_rejects_done_without_three_live_samples(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            stop = Path(directory) / "stop"
            fake = FakeSocketCaptureAdb(stop, ["ready", "active", "done"])
            with patch.object(OBSERVER.subprocess, "run", fake.run), patch.object(OBSERVER.time, "sleep"), self.assertRaisesRegex(ValueError, "insufficient"):
                OBSERVER.observe("adb", "private-serial", RUN_ID, stop, 2)
            self.assertEqual(fake.acknowledgements, [f"{RUN_ID}:ipv4:start"])

    def test_rejects_mismatched_host_device_and_requested_profile(self) -> None:
        with self.assertRaisesRegex(ValueError, "profile does not match"):
            self.validate(valid_evidence(), expected_profile="physical_kernel_lt57")

    def test_legacy_cached_eligibility_without_live_armed_state_cannot_pass(self) -> None:
        evidence = valid_evidence()
        evidence.update(profile="physical_kernel_lt57", kernelFamily="4.19", apiLevel=30,
            mapDns=None, families=[], socketTable=[], legacy={
            "bindFailureKind": "ERRNO", "bindFailureStage": "bind", "bindErrno": 1,
            "distinctUidVerified": True, "vpnTcpRoundTrips": 1, "vpnUdpRoundTrips": 1,
            "vpnTcpFixtureEvents": 1, "vpnUdpFixtureEvents": 1})
        evidence["qualification"] = {"unprivilegedBindToDevice": "permission_denied", "uidPolicyEligible": False, "uidPolicyArmed": False}
        evidence["qualification"].pop("uidPolicyArmed")
        with self.assertRaisesRegex(ValueError, "uidPolicyArmed"):
            self.validate(evidence)

    def test_legacy_rejects_an_armed_runtime_even_when_eligibility_is_false(self) -> None:
        evidence = valid_evidence()
        evidence.update(profile="physical_kernel_lt57", kernelFamily="4.19", apiLevel=30)
        evidence["qualification"] = {"unprivilegedBindToDevice": "permission_denied",
                                     "uidPolicyEligible": False, "uidPolicyArmed": True}
        with self.assertRaisesRegex(ValueError, "runtime UID policy is disarmed"):
            self.validate(evidence)

    def test_armed_profile_rejects_a_disarmed_runtime_snapshot(self) -> None:
        evidence = valid_evidence()
        evidence["qualification"]["uidPolicyArmed"] = False
        with self.assertRaisesRegex(ValueError, "armed evidence"):
            self.validate(evidence)

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
        evidence["kernelFamily"] = "unknown"
        with self.assertRaisesRegex(ValueError, "device facts"):
            self.validate(evidence)


if __name__ == "__main__":
    unittest.main()
