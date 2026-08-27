#!/usr/bin/env python3
"""Validate redacted IPv4/IPv6 Android SO_BINDTODEVICE evidence."""

from __future__ import annotations

import argparse
import ipaddress
import json
import re
import time
from pathlib import Path
from typing import Any


VERSION = "android_so_bind_physical_evidence_v4"
PROFILE = "physical_kernel_ge57"
LEGACY_PROFILE = "physical_kernel_lt57"
FAMILIES = ("ipv4", "ipv6")
POSITIVE_COUNTERS = (
    "directTcpRoundTrips",
    "directUdpRoundTrips",
    "directTcpFixtureEvents",
    "directUdpFixtureEvents",
    "allowedTcpRoundTrips",
    "allowedUdpRoundTrips",
    "allowedTcpFixtureEvents",
    "allowedUdpFixtureEvents",
    "deniedTcpBlockedAttempts",
    "deniedUdpBlockedAttempts",
    "livenessTcpRoundTrips",
    "livenessUdpRoundTrips",
    "livenessTcpFixtureEvents",
    "livenessUdpFixtureEvents",
)
EXACT_ONE_COUNTERS = (
    "directIcmpEchoReplies",
    "directIcmpFixtureEvents",
    "allowedUidIcmpBlockedAttempts",
    "deniedUidIcmpBlockedAttempts",
    "livenessIcmpEchoReplies",
    "livenessIcmpFixtureEvents",
    "allowedUidIcmpIngressPackets",
    "deniedUidIcmpIngressPackets",
)
ZERO_COUNTERS = (
    "deniedTcpFixtureEvents",
    "deniedUdpFixtureEvents",
    "allowedUidIcmpFixtureEvents",
    "deniedUidIcmpFixtureEvents",
)
TOP_LEVEL_FIELDS = {
    "version",
    "status",
    "profile",
    "runId",
    "sourceSha",
    "appApkSha256",
    "testApkSha256",
    "startedAtEpochMs",
    "finishedAtEpochMs",
    "deviceManufacturer",
    "deviceCodename",
    "apiLevel",
    "kernelFamily",
    "realTun",
    "tunPacketPathObserved",
    "mapDns",
    "families",
    "socketTable",
    "qualification",
    "legacy",
}
HEX_32 = re.compile(r"[0-9a-f]{32}")
HEX_40 = re.compile(r"[0-9a-f]{40}")
HEX_64 = re.compile(r"[0-9a-f]{64}")
MAX_RUN_DURATION_MS = 5 * 60 * 1000
MAX_EVIDENCE_AGE_MS = 10 * 60 * 1000
FAMILY_FIELDS = {
    "family",
    "icmpProtocol",
    "sourceFamilyVerified",
    "deniedTcpErrno",
    "deniedTcpFailureKind",
    "deniedTcpFailureStage",
    "deniedUdpErrno",
    "deniedUdpFailureKind",
    "deniedUdpFailureStage",
    "allowedUidIcmpErrno",
    "allowedUidIcmpFailureKind",
    "allowedUidIcmpFailureStage",
    "deniedUidIcmpErrno",
    "deniedUidIcmpFailureKind",
    "deniedUidIcmpFailureStage",
    *POSITIVE_COUNTERS,
    *EXACT_ONE_COUNTERS,
    *ZERO_COUNTERS,
}
MAPDNS_FIELDS = {
    "addressFamily",
    "syntheticEndpoint",
    "armedAllowlistVerified",
    "armedControlFailureKind",
    "armedControlFailureStage",
    "armedControlErrno",
    "allowedRoundTrips",
    "allowedExactAnswerVerified",
    "allowedResolverEvents",
    "allowedDnsQueriesDelta",
    "deniedBlockedAttempts",
    "deniedFailureKind",
    "deniedFailureStage",
    "deniedErrno",
    "deniedResolverEvents",
    "deniedDnsQueriesDelta",
}
TCP_BLOCK_FAILURE_KINDS = {"CONNECTION_RESET", "ERRNO", "TIMEOUT"}
TCP_NETWORK_STAGES = {"connect", "receive", "send"}
TCP_UNREACHABLE_ERRNOS = {101, 113}
TCP_RESET_ERRNOS = {104}
SOCKET_TIMEOUT_ERRNOS = {11, 110}
UDP_BLOCK_FAILURE_KINDS = {"ERRNO", "TIMEOUT"}
ICMP_BLOCK_FAILURE_KINDS = {"ERRNO", "TIMEOUT"}


def summarize_socket_sample(tcp: str, tcp6: str, *, uid: int, host: str, control_port: int, denied_port: int) -> dict[str, int]:
    """Parse private adb snapshots. Only redacted counts may leave this function."""
    target = ipaddress.ip_address(host)
    if control_port == denied_port or not 0 < control_port <= 65535 or not 0 < denied_port <= 65535:
        raise ValueError("socket control and denied endpoints must be distinct")
    positive = 0
    for text, width in ((tcp, 8), (tcp6, 32)):
        lines = text.splitlines()
        if not lines or not re.fullmatch(r"\s*sl\s+local_address\s+(?:rem_address|remote_address)\s+st\s+tx_queue\s+rx_queue\s+tr\s+tm->when\s+retrnsmt\s+uid\s+timeout\s+inode\s*", lines[0]):
            raise ValueError("unreadable or malformed socket table header")
        for line in lines[1:]:
            fields = line.split()
            if len(fields) < 10 or not re.fullmatch(r"[0-9]+:", fields[0]):
                raise ValueError("malformed socket table row")
            endpoint = re.fullmatch(rf"([0-9A-Fa-f]{{{width}}}):([0-9A-Fa-f]{{4}})", fields[2])
            local = re.fullmatch(rf"[0-9A-Fa-f]{{{width}}}:[0-9A-Fa-f]{{4}}", fields[1])
            if not endpoint or not local or not re.fullmatch(r"[0-9A-Fa-f]{2}", fields[3]) or not fields[7].isdecimal() or not fields[9].isdecimal():
                raise ValueError("malformed socket table tuple")
            if (not re.fullmatch(r"[0-9A-Fa-f]{8}:[0-9A-Fa-f]{8}", fields[4])
                    or not re.fullmatch(r"[0-9A-Fa-f]{2}:[0-9A-Fa-f]{8}", fields[5])
                    or not re.fullmatch(r"[0-9A-Fa-f]{8}", fields[6]) or not fields[8].isdecimal()):
                raise ValueError("malformed socket table queue or timer")
            packed = bytes.fromhex(endpoint[1])
            address = ipaddress.ip_address(b"".join(packed[n:n+4][::-1] for n in range(0, len(packed), 4)))
            if isinstance(address, ipaddress.IPv6Address) and address.ipv4_mapped:
                address = address.ipv4_mapped
            port = int(endpoint[2], 16)
            if int(fields[7]) == uid and address == target:
                if port == denied_port:
                    raise ValueError("leaked app-owned remote connection observed")
                if port == control_port and fields[3] == "01":
                    positive += 1
    if positive == 0:
        raise ValueError("socket table lacks the established positive visibility control")
    return {"positiveControlRows": positive, "deniedRemoteRows": 0}


def validate_icmp_block(record: dict[str, Any], family: str, prefix: str) -> None:
    failure_kind = record[f"{prefix}FailureKind"]
    failure_stage = record[f"{prefix}FailureStage"]
    failure_errno = record[f"{prefix}Errno"]
    if failure_kind not in ICMP_BLOCK_FAILURE_KINDS:
        raise ValueError(f"{family}.{prefix}FailureKind is not a blocked ICMP outcome")
    if type(failure_errno) is not int or failure_errno <= 0:
        raise ValueError(f"{family}.{prefix}Errno must be a positive integer")
    if failure_kind == "ERRNO" and (
        failure_stage != "connect" or failure_errno not in TCP_UNREACHABLE_ERRNOS
    ):
        raise ValueError(
            f"{family}.{prefix} generic errno is not an unreachable connect outcome"
        )
    if failure_kind == "TIMEOUT" and (
        failure_stage != "receive" or failure_errno not in SOCKET_TIMEOUT_ERRNOS
    ):
        raise ValueError(f"{family}.{prefix} timeout kind/stage/errno is inconsistent")


def require_exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise ValueError(
            f"{label} fields mismatch missing={sorted(expected - actual)} extra={sorted(actual - expected)}"
        )


def validate(
    path: Path,
    *,
    expected_profile: str | None = None,
    expected_device_manufacturer: str | None = None,
    expected_device_codename: str | None = None,
    expected_api_level: int | None = None,
    expected_run_id: str | None = None,
    expected_source_sha: str | None = None,
    expected_app_apk_sha256: str | None = None,
    expected_test_apk_sha256: str | None = None,
    expected_started_at_epoch_ms: int | None = None,
    now_epoch_ms: int | None = None,
) -> str:
    raw = path.read_bytes()
    if len(raw) > 32_768:
        raise ValueError("evidence exceeds the 32 KiB redacted artifact limit")
    document = json.loads(raw)
    if not isinstance(document, dict):
        raise ValueError("evidence root must be an object")
    require_exact_fields(document, TOP_LEVEL_FIELDS, "evidence")
    if document["version"] != VERSION:
        raise ValueError("unsupported evidence version")
    if document["status"] != "PASS" or document["profile"] not in (PROFILE, LEGACY_PROFILE):
        raise ValueError("evidence status/profile is not the qualified physical PASS")
    kernel = document["kernelFamily"]
    if not isinstance(kernel, str) or not re.fullmatch(r"[0-9]{1,2}\.[0-9]{1,3}", kernel):
        raise ValueError("device facts require a parsed kernel family")
    modern = tuple(map(int, kernel.split("."))) >= (5, 7)
    if modern != (document["profile"] == PROFILE):
        raise ValueError("device facts do not match the requested kernel profile")
    if type(document["apiLevel"]) is not int or document["apiLevel"] < 29:
        raise ValueError("device facts require API 29+ for the UID lookup API")
    for field in ("deviceManufacturer", "deviceCodename"):
        if not isinstance(document[field], str) or not re.fullmatch(r"[A-Za-z0-9 _.-]{1,64}", document[field]):
            raise ValueError("device facts are malformed")
    qualification = document["qualification"]
    if not isinstance(qualification, dict):
        raise ValueError("qualification must be an object")
    require_exact_fields(qualification, {"unprivilegedBindToDevice", "uidPolicyEligible", "uidPolicyArmed"}, "qualification")
    if type(qualification["uidPolicyEligible"]) is not bool:
        raise ValueError("production eligibility must be boolean")
    legacy = document["profile"] == LEGACY_PROFILE and qualification["unprivilegedBindToDevice"] == "permission_denied" and qualification["uidPolicyEligible"] is False
    if type(qualification["uidPolicyArmed"]) is not bool:
        raise ValueError("runtime uidPolicyArmed must be boolean")
    if legacy and qualification["uidPolicyArmed"] is not False:
        raise ValueError("legacy evidence must confirm runtime UID policy is disarmed")
    if not legacy and qualification != {"unprivilegedBindToDevice": "supported", "uidPolicyEligible": True, "uidPolicyArmed": True}:
        raise ValueError("armed evidence requires a supported production capability")
    if not isinstance(document["runId"], str) or not HEX_32.fullmatch(
        document["runId"]
    ):
        raise ValueError("runId must be 32 lowercase hexadecimal characters")
    if not isinstance(document["sourceSha"], str) or not HEX_40.fullmatch(
        document["sourceSha"]
    ):
        raise ValueError("sourceSha must be a full lowercase Git SHA")
    for field in ("appApkSha256", "testApkSha256"):
        if not isinstance(document[field], str) or not HEX_64.fullmatch(
            document[field]
        ):
            raise ValueError(f"{field} must be a lowercase SHA-256 digest")
    started_at = document["startedAtEpochMs"]
    finished_at = document["finishedAtEpochMs"]
    if type(started_at) is not int or type(finished_at) is not int:
        raise ValueError("capture timestamps must be integers")
    if finished_at < started_at or finished_at - started_at > MAX_RUN_DURATION_MS:
        raise ValueError("capture window is negative or exceeds five minutes")
    now = time.time_ns() // 1_000_000 if now_epoch_ms is None else now_epoch_ms
    if finished_at > now + 60_000 or now - finished_at > MAX_EVIDENCE_AGE_MS:
        raise ValueError("physical evidence is stale or from the future")
    expected_values = {
        "profile": expected_profile,
        "deviceManufacturer": expected_device_manufacturer,
        "deviceCodename": expected_device_codename,
        "apiLevel": expected_api_level,
        "runId": expected_run_id,
        "sourceSha": expected_source_sha,
        "appApkSha256": expected_app_apk_sha256,
        "testApkSha256": expected_test_apk_sha256,
        "startedAtEpochMs": expected_started_at_epoch_ms,
    }
    for field, expected in expected_values.items():
        if expected is not None and document[field] != expected:
            raise ValueError(f"{field} does not match the current run")
    if document["realTun"] is not True or document["tunPacketPathObserved"] is not True:
        raise ValueError("physical TUN packet-path observation is missing")

    if legacy:
        facts = document["legacy"]
        if not isinstance(facts, dict):
            raise ValueError("legacy permission-denied evidence requires liveness facts")
        require_exact_fields(facts, {"bindFailureKind", "bindFailureStage", "bindErrno", "distinctUidVerified",
                                    "vpnTcpRoundTrips", "vpnUdpRoundTrips", "vpnTcpFixtureEvents", "vpnUdpFixtureEvents"}, "legacy")
        if facts["bindFailureKind"] != "ERRNO" or facts["bindFailureStage"] != "bind" or type(facts["bindErrno"]) is not int or facts["bindErrno"] not in (1, 13):
            raise ValueError("legacy bind must fail with EPERM/EACCES at SO_BINDTODEVICE")
        if facts["distinctUidVerified"] is not True:
            raise ValueError("legacy probe must run under a distinct UID")
        for field in ("vpnTcpRoundTrips", "vpnUdpRoundTrips", "vpnTcpFixtureEvents", "vpnUdpFixtureEvents"):
            if type(facts[field]) is not int or facts[field] != 1:
                raise ValueError("legacy VPN liveness must contain one exact round trip and fixture event")
        if document["mapDns"] is not None or document["families"] != [] or document["socketTable"] != []:
            raise ValueError("legacy disarmed evidence must not claim armed denial")
        return "legacy_ipv4"
    if document["legacy"] is not None:
        raise ValueError("armed evidence must not contain legacy disarmed facts")

    samples = document["socketTable"]
    if not isinstance(samples, list) or len(samples) != 2:
        raise ValueError("socketTable must contain both live family observations")
    for family, record in zip(FAMILIES, samples):
        if not isinstance(record, dict):
            raise ValueError("socketTable record must be an object")
        require_exact_fields(record, {"family", "liveSamples", "minimumPositiveControlRows", "deniedRemoteRows", "synchronized"}, "socketTable")
        if record["family"] != family or record["synchronized"] is not True:
            raise ValueError("socketTable family order or synchronization missing")
        for field, minimum in (("liveSamples", 3), ("minimumPositiveControlRows", 1)):
            if type(record[field]) is not int or record[field] < minimum:
                raise ValueError("socketTable requires live samples and positive visibility control")
        if type(record["deniedRemoteRows"]) is not int or record["deniedRemoteRows"] != 0:
            raise ValueError("socketTable contains leaked remote connections")

    mapdns = document["mapDns"]
    if not isinstance(mapdns, dict):
        raise ValueError("mapDns must be an object")
    require_exact_fields(mapdns, MAPDNS_FIELDS, "mapDns")
    if mapdns["addressFamily"] != "ipv4" or mapdns["syntheticEndpoint"] != "198.18.0.53:53":
        raise ValueError("mapDns must describe the single IPv4 synthetic endpoint")
    if mapdns["armedAllowlistVerified"] is not True:
        raise ValueError("mapDns native allowlist was not proven armed")
    armed_kind = mapdns["armedControlFailureKind"]
    armed_stage = mapdns["armedControlFailureStage"]
    armed_errno = mapdns["armedControlErrno"]
    if armed_stage not in TCP_NETWORK_STAGES:
        raise ValueError("mapDns armed control did not fail at a network stage")
    if type(armed_errno) is not int or armed_errno <= 0:
        raise ValueError("mapDns.armedControlErrno must be a positive integer")
    if armed_kind == "ERRNO":
        if armed_stage != "connect" or armed_errno not in TCP_UNREACHABLE_ERRNOS:
            raise ValueError("mapDns armed control generic errno is not an unreachable connect outcome")
    elif armed_kind == "TIMEOUT":
        if armed_errno not in SOCKET_TIMEOUT_ERRNOS:
            raise ValueError("mapDns armed control timeout kind/errno pair is inconsistent")
    elif armed_kind == "CONNECTION_RESET":
        if armed_errno not in TCP_RESET_ERRNOS:
            raise ValueError("mapDns armed control reset kind/errno pair is inconsistent")
    else:
        raise ValueError("mapDns armed control failure kind is not a blocked outcome")
    if mapdns["allowedExactAnswerVerified"] is not True:
        raise ValueError("mapDns exact fixture answer was not verified")
    for field in (
        "allowedRoundTrips",
        "allowedResolverEvents",
        "allowedDnsQueriesDelta",
        "deniedBlockedAttempts",
    ):
        if type(mapdns[field]) is not int or mapdns[field] != 1:
            raise ValueError(f"mapDns.{field} must equal one")
    for field in ("deniedResolverEvents", "deniedDnsQueriesDelta"):
        if type(mapdns[field]) is not int or mapdns[field] != 0:
            raise ValueError(f"mapDns.{field} must equal zero")
    mapdns_failure_kind = mapdns["deniedFailureKind"]
    mapdns_failure_stage = mapdns["deniedFailureStage"]
    mapdns_failure_errno = mapdns["deniedErrno"]
    if mapdns_failure_kind not in UDP_BLOCK_FAILURE_KINDS:
        raise ValueError("mapDns.deniedFailureKind is not a blocked outcome")
    if mapdns_failure_stage not in TCP_NETWORK_STAGES:
        raise ValueError("mapDns.deniedFailureStage is not a network stage")
    if type(mapdns_failure_errno) is not int or mapdns_failure_errno <= 0:
        raise ValueError("mapDns.deniedErrno must be a positive integer")
    if mapdns_failure_kind == "ERRNO" and (
        mapdns_failure_stage != "connect" or mapdns_failure_errno not in TCP_UNREACHABLE_ERRNOS
    ):
        raise ValueError("mapDns generic errno is not an unreachable connect outcome")
    if mapdns_failure_kind == "TIMEOUT" and mapdns_failure_errno not in SOCKET_TIMEOUT_ERRNOS:
        raise ValueError("mapDns timeout kind/errno pair is inconsistent")

    families = document["families"]
    if not isinstance(families, list) or len(families) != len(FAMILIES):
        raise ValueError("evidence must contain exactly IPv4 and IPv6 family records")
    by_family: dict[str, dict[str, Any]] = {}
    for index, record in enumerate(families):
        if not isinstance(record, dict):
            raise ValueError(f"family record {index} must be an object")
        require_exact_fields(record, FAMILY_FIELDS, f"family record {index}")
        family = record["family"]
        if family not in FAMILIES or family in by_family:
            raise ValueError(f"unexpected or duplicate family: {family!r}")
        if record["sourceFamilyVerified"] is not True:
            raise ValueError(f"{family} source family was not verified")
        expected_icmp_protocol = "icmpv6" if family == "ipv6" else "icmpv4"
        if record["icmpProtocol"] != expected_icmp_protocol:
            raise ValueError(f"{family}.icmpProtocol does not match the address family")
        failure_kind = record["deniedTcpFailureKind"]
        failure_stage = record["deniedTcpFailureStage"]
        failure_errno = record["deniedTcpErrno"]
        if failure_kind not in TCP_BLOCK_FAILURE_KINDS:
            raise ValueError(f"{family}.deniedTcpFailureKind is not a blocked outcome")
        if failure_stage not in TCP_NETWORK_STAGES:
            raise ValueError(f"{family}.deniedTcpFailureStage is not a network stage")
        if type(failure_errno) is not int or failure_errno <= 0:
            raise ValueError(f"{family}.deniedTcpErrno must be a positive integer")
        if failure_kind == "ERRNO" and (
            failure_stage != "connect" or failure_errno not in TCP_UNREACHABLE_ERRNOS
        ):
            raise ValueError(f"{family} generic errno is not an unreachable connect outcome")
        if failure_kind == "CONNECTION_RESET" and failure_errno not in TCP_RESET_ERRNOS:
            raise ValueError(f"{family} reset kind/errno pair is inconsistent")
        if failure_kind == "TIMEOUT" and failure_errno not in SOCKET_TIMEOUT_ERRNOS:
            raise ValueError(f"{family} TCP timeout kind/errno pair is inconsistent")
        udp_failure_kind = record["deniedUdpFailureKind"]
        udp_failure_stage = record["deniedUdpFailureStage"]
        udp_failure_errno = record["deniedUdpErrno"]
        if udp_failure_kind not in UDP_BLOCK_FAILURE_KINDS:
            raise ValueError(f"{family}.deniedUdpFailureKind is not a blocked outcome")
        if udp_failure_stage not in TCP_NETWORK_STAGES:
            raise ValueError(f"{family}.deniedUdpFailureStage is not a network stage")
        if type(udp_failure_errno) is not int or udp_failure_errno <= 0:
            raise ValueError(f"{family}.deniedUdpErrno must be a positive integer")
        if udp_failure_kind == "ERRNO" and (
            udp_failure_stage != "connect"
            or udp_failure_errno not in TCP_UNREACHABLE_ERRNOS
        ):
            raise ValueError(
                f"{family} UDP generic errno is not an unreachable connect outcome"
            )
        if (
            udp_failure_kind == "TIMEOUT"
            and udp_failure_errno not in SOCKET_TIMEOUT_ERRNOS
        ):
            raise ValueError(f"{family} UDP timeout kind/errno pair is inconsistent")
        validate_icmp_block(record, family, "allowedUidIcmp")
        validate_icmp_block(record, family, "deniedUidIcmp")
        for counter in POSITIVE_COUNTERS:
            value = record[counter]
            if type(value) is not int or value < 1:
                raise ValueError(f"{family}.{counter} must be a positive integer")
        for counter in EXACT_ONE_COUNTERS:
            value = record[counter]
            if type(value) is not int or value != 1:
                raise ValueError(f"{family}.{counter} must equal one")
        for counter in ZERO_COUNTERS:
            value = record[counter]
            if type(value) is not int or value != 0:
                raise ValueError(f"{family}.{counter} must be zero")
        by_family[family] = record
    if tuple(by_family) != FAMILIES:
        raise ValueError("family evidence must be ordered ipv4 then ipv6")
    return "armed_dual_stack"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--profile", required=True, choices=(PROFILE, LEGACY_PROFILE))
    parser.add_argument("--device-manufacturer", required=True)
    parser.add_argument("--device-codename", required=True)
    parser.add_argument("--api-level", required=True, type=int)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--app-apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--started-at-epoch-ms", required=True, type=int)
    args = parser.parse_args()
    try:
        scope = validate(
            args.evidence,
            expected_profile=args.profile,
            expected_device_manufacturer=args.device_manufacturer,
            expected_device_codename=args.device_codename,
            expected_api_level=args.api_level,
            expected_run_id=args.run_id,
            expected_source_sha=args.source_sha,
            expected_app_apk_sha256=args.app_apk_sha256,
            expected_test_apk_sha256=args.test_apk_sha256,
            expected_started_at_epoch_ms=args.started_at_epoch_ms,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"Android SO_BIND physical evidence validation failed: {error}")
        return 1
    if scope == "legacy_ipv4":
        print("pre-5.7 SO_BINDTODEVICE permission denial and ordinary IPv4 VPN liveness")
    else:
        print("armed UID denial and synchronized IPv4/IPv6 socket observation")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
