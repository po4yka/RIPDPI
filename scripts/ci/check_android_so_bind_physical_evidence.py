#!/usr/bin/env python3
"""Validate redacted IPv4/IPv6 Android SO_BINDTODEVICE evidence."""

from __future__ import annotations

import argparse
import json
import re
import time
from pathlib import Path
from typing import Any


VERSION = "android_so_bind_physical_evidence_v2"
PROFILE = "physical_pixel_api37_kernel61"
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
ZERO_COUNTERS = ("deniedTcpFixtureEvents", "deniedUdpFixtureEvents")
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
    "families",
}
HEX_32 = re.compile(r"[0-9a-f]{32}")
HEX_40 = re.compile(r"[0-9a-f]{40}")
HEX_64 = re.compile(r"[0-9a-f]{64}")
MAX_RUN_DURATION_MS = 5 * 60 * 1000
MAX_EVIDENCE_AGE_MS = 10 * 60 * 1000
FAMILY_FIELDS = {
    "family",
    "sourceFamilyVerified",
    "deniedTcpErrno",
    "deniedTcpFailureKind",
    "deniedTcpFailureStage",
    "deniedUdpErrno",
    "deniedUdpFailureKind",
    "deniedUdpFailureStage",
    *POSITIVE_COUNTERS,
    *ZERO_COUNTERS,
}
TCP_BLOCK_FAILURE_KINDS = {"CONNECTION_RESET", "ERRNO", "TIMEOUT"}
TCP_NETWORK_STAGES = {"connect", "receive", "send"}
TCP_UNREACHABLE_ERRNOS = {101, 113}
TCP_RESET_ERRNOS = {104}
SOCKET_TIMEOUT_ERRNOS = {11, 110}
UDP_BLOCK_FAILURE_KINDS = {"ERRNO", "TIMEOUT"}


def require_exact_fields(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise ValueError(
            f"{label} fields mismatch missing={sorted(expected - actual)} extra={sorted(actual - expected)}"
        )


def validate(
    path: Path,
    *,
    expected_run_id: str | None = None,
    expected_source_sha: str | None = None,
    expected_app_apk_sha256: str | None = None,
    expected_test_apk_sha256: str | None = None,
    expected_started_at_epoch_ms: int | None = None,
    now_epoch_ms: int | None = None,
) -> None:
    raw = path.read_bytes()
    if len(raw) > 32_768:
        raise ValueError("evidence exceeds the 32 KiB redacted artifact limit")
    document = json.loads(raw)
    if not isinstance(document, dict):
        raise ValueError("evidence root must be an object")
    require_exact_fields(document, TOP_LEVEL_FIELDS, "evidence")
    if document["version"] != VERSION:
        raise ValueError("unsupported evidence version")
    if document["status"] != "PASS" or document["profile"] != PROFILE:
        raise ValueError("evidence status/profile is not the qualified physical PASS")
    if (
        document["deviceManufacturer"] != "Google"
        or document["deviceCodename"] != "panther"
        or document["apiLevel"] != 37
        or document["kernelFamily"] != "6.1"
    ):
        raise ValueError(
            "evidence device facts do not match the qualified Pixel 7 profile"
        )
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
        for counter in POSITIVE_COUNTERS:
            value = record[counter]
            if type(value) is not int or value < 1:
                raise ValueError(f"{family}.{counter} must be a positive integer")
        for counter in ZERO_COUNTERS:
            value = record[counter]
            if type(value) is not int or value != 0:
                raise ValueError(f"{family}.{counter} must be zero")
        by_family[family] = record
    if tuple(by_family) != FAMILIES:
        raise ValueError("family evidence must be ordered ipv4 then ipv6")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--app-apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--started-at-epoch-ms", required=True, type=int)
    args = parser.parse_args()
    try:
        validate(
            args.evidence,
            expected_run_id=args.run_id,
            expected_source_sha=args.source_sha,
            expected_app_apk_sha256=args.app_apk_sha256,
            expected_test_apk_sha256=args.test_apk_sha256,
            expected_started_at_epoch_ms=args.started_at_epoch_ms,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        print(f"Android SO_BIND physical evidence validation failed: {error}")
        return 1
    print("Validated physical Android SO_BIND evidence for IPv4 and IPv6.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
