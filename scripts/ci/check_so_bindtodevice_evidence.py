#!/usr/bin/env python3
"""Validate redacted physical SO_BINDTODEVICE/TUN evidence."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


VERSION = "so_bindtodevice_tun_evidence_v1"
TARGET = "so_bindtodevice_e2e"
PHASES = (
    "ipv4_direct_tcp",
    "ipv4_direct_udp",
    "ipv4_allowed_tcp",
    "ipv4_allowed_udp",
    "ipv4_denied_tcp",
    "ipv4_denied_udp",
    "ipv6_direct_tcp",
    "ipv6_direct_udp",
    "ipv6_allowed_tcp",
    "ipv6_allowed_udp",
    "ipv6_denied_tcp",
    "ipv6_denied_udp",
)
ROOT_FIELDS = {"capabilities", "cleanupVerified", "phases", "provenance", "result", "version"}
CAPABILITY_FIELDS = {"ipv4", "ipv6", "realTun", "unprivilegedSoBindToDevice"}
PROVENANCE_FIELDS = {"sourceSha", "testTarget", "workflowRunAttempt", "workflowRunId"}
PHASE_FIELDS = {
    "family",
    "id",
    "outcome",
    "peerEvents",
    "protocol",
    "socksEvents",
    "tunInboundPackets",
    "tunOutboundPackets",
    "tunOutboundResets",
}


def canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode()


def exact_fields(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{label} fields must be exactly {sorted(expected)}")
    return value


def nonnegative_int(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{label} must be a non-negative integer")
    return value


def validate(
    path: Path,
    *,
    expected_source_sha: str,
    expected_run_id: str,
    expected_run_attempt: str,
) -> dict[str, Any]:
    raw = path.read_bytes()
    manifest = json.loads(raw)
    if raw != canonical_bytes(manifest):
        raise ValueError("manifest must be canonical JSON")
    root = exact_fields(manifest, ROOT_FIELDS, "manifest")
    if root["version"] != VERSION or root["result"] != "PASS":
        raise ValueError("manifest version/result mismatch")
    if root["cleanupVerified"] is not True:
        raise ValueError("cleanupVerified must be true")

    capabilities = exact_fields(root["capabilities"], CAPABILITY_FIELDS, "capabilities")
    if any(capabilities[field] is not True for field in CAPABILITY_FIELDS):
        raise ValueError("every physical capability must be true")

    provenance = exact_fields(root["provenance"], PROVENANCE_FIELDS, "provenance")
    if provenance["sourceSha"] != expected_source_sha or not re.fullmatch(r"[0-9a-f]{40}", expected_source_sha):
        raise ValueError("source SHA mismatch")
    if provenance["workflowRunId"] != expected_run_id or not expected_run_id.isdigit():
        raise ValueError("workflow run id mismatch")
    if provenance["workflowRunAttempt"] != expected_run_attempt or not expected_run_attempt.isdigit():
        raise ValueError("workflow run attempt mismatch")
    if provenance["testTarget"] != TARGET:
        raise ValueError("test target mismatch")

    phases = root["phases"]
    if not isinstance(phases, list) or [phase.get("id") for phase in phases if isinstance(phase, dict)] != list(PHASES):
        raise ValueError("phase ids/order mismatch")
    for phase in phases:
        phase = exact_fields(phase, PHASE_FIELDS, "phase")
        phase_id = phase["id"]
        family, contract, protocol = phase_id.split("_", 2)
        if phase["family"] != family or phase["protocol"] != protocol or phase["outcome"] != "PASS":
            raise ValueError(f"{phase_id} identity/outcome mismatch")
        inbound = nonnegative_int(phase["tunInboundPackets"], f"{phase_id}.tunInboundPackets")
        outbound = nonnegative_int(phase["tunOutboundPackets"], f"{phase_id}.tunOutboundPackets")
        resets = nonnegative_int(phase["tunOutboundResets"], f"{phase_id}.tunOutboundResets")
        socks = nonnegative_int(phase["socksEvents"], f"{phase_id}.socksEvents")
        peer = nonnegative_int(phase["peerEvents"], f"{phase_id}.peerEvents")
        if contract == "direct" and (inbound, outbound, resets, socks, peer) != (0, 0, 0, 0, 1):
            raise ValueError(f"{phase_id} direct-path control mismatch")
        if contract == "allowed" and not (inbound > 0 and outbound > 0 and resets == 0 and socks > 0 and peer == 1):
            raise ValueError(f"{phase_id} allowed-path evidence mismatch")
        if contract == "denied" and protocol == "tcp" and not (
            inbound > 0 and outbound > 0 and resets == 1 and socks == 0 and peer == 0
        ):
            raise ValueError(f"{phase_id} denied TCP evidence mismatch")
        if contract == "denied" and protocol == "udp" and (outbound, resets, socks, peer) != (0, 0, 0, 0):
            raise ValueError(f"{phase_id} denied UDP evidence mismatch")
        if contract == "denied" and inbound == 0:
            raise ValueError(f"{phase_id} did not physically enter tun0")
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--expected-source-sha", required=True)
    parser.add_argument("--expected-run-id", required=True)
    parser.add_argument("--expected-run-attempt", required=True)
    args = parser.parse_args()
    try:
        validate(
            args.manifest,
            expected_source_sha=args.expected_source_sha,
            expected_run_id=args.expected_run_id,
            expected_run_attempt=args.expected_run_attempt,
        )
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"SO_BINDTODEVICE evidence validation failed: {error}", file=sys.stderr)
        return 1
    print(f"Validated {len(PHASES)} physical SO_BINDTODEVICE/TUN phases.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
