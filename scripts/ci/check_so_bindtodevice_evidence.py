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
FAILURE_ROOT_FIELDS = {"cleanupVerified", "provenance", "reasonCode", "result", "version"}
CAPABILITY_FIELDS = {"ipv4", "ipv6", "realTun", "unprivilegedSoBindToDevice"}
PROVENANCE_FIELDS = {"sourceSha", "testTarget", "workflowRunAttempt", "workflowRunId"}
FAILURE_CLEANUP = {
    "INFRA_GAP": {
        "DIRTY_TOPOLOGY": False,
        "PLATFORM_UNSUPPORTED": False,
        "PREREQUISITE_SETUP_FAILED": False,
        "PRIVILEGE_MISSING": False,
        "RUN_NOT_AUTHORIZED": False,
        "TEST_BINARY_INVALID": False,
        "TOOL_MISSING": False,
        "TOPOLOGY_INSPECTION_FAILED": False,
        "TUN_UNAVAILABLE": False,
    },
    "TEST_FAILURE": {
        "ADVERSARIAL_TEST_FAILED": True,
        "BUILD_FAILED": False,
        "CLEANUP_FAILED": False,
        "EVIDENCE_INVALID": True,
        "EVIDENCE_MALFORMED_UNVERIFIED": False,
        "EVIDENCE_MISSING": True,
        "EVIDENCE_NOT_PRODUCED": False,
        "RUNTIME_FAILED": False,
    },
}
FAILURE_REASONS = {result: set(reasons) for result, reasons in FAILURE_CLEANUP.items()}
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


def provenance(
    *,
    source_sha: str,
    run_id: str,
    run_attempt: str,
) -> dict[str, str]:
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("source SHA must be 40 lowercase hex characters")
    if not run_id.isdigit() or not run_attempt.isdigit():
        raise ValueError("workflow run id/attempt must be decimal integers")
    return {
        "sourceSha": source_sha,
        "testTarget": TARGET,
        "workflowRunAttempt": run_attempt,
        "workflowRunId": run_id,
    }


def failure_manifest(
    *,
    result: str,
    reason_code: str,
    cleanup_verified: bool,
    source_sha: str,
    run_id: str,
    run_attempt: str,
) -> dict[str, Any]:
    if result not in FAILURE_REASONS:
        raise ValueError(f"unsupported failure result: {result}")
    if reason_code not in FAILURE_REASONS[result]:
        raise ValueError(f"reason code {reason_code} is invalid for {result}")
    if cleanup_verified is not FAILURE_CLEANUP[result][reason_code]:
        raise ValueError(f"cleanupVerified is invalid for {result}/{reason_code}")
    return {
        "cleanupVerified": cleanup_verified,
        "provenance": provenance(
            source_sha=source_sha,
            run_id=run_id,
            run_attempt=run_attempt,
        ),
        "reasonCode": reason_code,
        "result": result,
        "version": VERSION,
    }


def write_failure_manifest(path: Path, manifest: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_bytes(manifest))


def finalize_workflow_evidence(
    path: Path,
    *,
    prerequisite_outcome: str,
    build_outcome: str,
    runtime_outcome: str,
    source_sha: str,
    run_id: str,
    run_attempt: str,
) -> dict[str, Any]:
    allowed_outcomes = {"cancelled", "failure", "skipped", "success"}
    outcomes = {
        "prerequisite": prerequisite_outcome,
        "build": build_outcome,
        "runtime": runtime_outcome,
    }
    for label, outcome in outcomes.items():
        if outcome not in allowed_outcomes:
            raise ValueError(f"invalid {label} step outcome: {outcome}")
    observed_manifest = None
    if path.exists():
        try:
            observed_manifest = validate(
                path,
                expected_source_sha=source_sha,
                expected_run_id=run_id,
                expected_run_attempt=run_attempt,
            )
        except (OSError, json.JSONDecodeError, ValueError):
            result = "TEST_FAILURE"
            reason_code = "EVIDENCE_MALFORMED_UNVERIFIED"
    if prerequisite_outcome != "success":
        result = "INFRA_GAP"
        reason_code = "PREREQUISITE_SETUP_FAILED"
    elif build_outcome != "success":
        result = "TEST_FAILURE"
        reason_code = "BUILD_FAILED"
    elif runtime_outcome != "success":
        if observed_manifest is not None and observed_manifest["result"] != "PASS":
            return observed_manifest
        if observed_manifest is not None or not path.exists():
            result = "TEST_FAILURE"
            reason_code = "RUNTIME_FAILED"
    elif observed_manifest is not None:
        return observed_manifest
    elif not path.exists():
        result = "TEST_FAILURE"
        reason_code = "EVIDENCE_NOT_PRODUCED"
    else:
        # A present manifest that failed validation was classified above.
        assert reason_code == "EVIDENCE_MALFORMED_UNVERIFIED"
    manifest = failure_manifest(
        result=result,
        reason_code=reason_code,
        cleanup_verified=FAILURE_CLEANUP[result][reason_code],
        source_sha=source_sha,
        run_id=run_id,
        run_attempt=run_attempt,
    )
    write_failure_manifest(path, manifest)
    return manifest


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
    if not isinstance(manifest, dict):
        raise ValueError("manifest must be an object")
    result = manifest.get("result")
    if not isinstance(result, str):
        raise ValueError("manifest result must be a string")
    root = exact_fields(
        manifest,
        ROOT_FIELDS if result == "PASS" else FAILURE_ROOT_FIELDS,
        "manifest",
    )
    if root["version"] != VERSION:
        raise ValueError("manifest version mismatch")

    expected_provenance = provenance(
        source_sha=expected_source_sha,
        run_id=expected_run_id,
        run_attempt=expected_run_attempt,
    )
    observed_provenance = exact_fields(root["provenance"], PROVENANCE_FIELDS, "provenance")
    if observed_provenance != expected_provenance:
        raise ValueError("manifest provenance mismatch")

    if result in FAILURE_REASONS:
        if not isinstance(root["cleanupVerified"], bool):
            raise ValueError("cleanupVerified must be a boolean")
        reason_code = root["reasonCode"]
        if not isinstance(reason_code, str):
            raise ValueError("reasonCode must be a string")
        if reason_code not in FAILURE_REASONS[result]:
            raise ValueError(f"reasonCode is invalid for {result}")
        if root["cleanupVerified"] is not FAILURE_CLEANUP[result][reason_code]:
            raise ValueError(f"cleanupVerified is invalid for {result}/{reason_code}")
        return manifest
    if result != "PASS":
        raise ValueError("manifest result mismatch")
    if root["cleanupVerified"] is not True:
        raise ValueError("cleanupVerified must be true")

    capabilities = exact_fields(root["capabilities"], CAPABILITY_FIELDS, "capabilities")
    if any(capabilities[field] is not True for field in CAPABILITY_FIELDS):
        raise ValueError("every physical capability must be true")

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
    parser.add_argument("--write-failure-result", choices=sorted(FAILURE_REASONS))
    parser.add_argument("--reason-code")
    parser.add_argument("--cleanup-verified", action="store_true")
    parser.add_argument("--finalize-prerequisite-outcome")
    parser.add_argument("--finalize-build-outcome")
    parser.add_argument("--finalize-runtime-outcome")
    args = parser.parse_args()
    try:
        finalize_outcomes = (
            args.finalize_prerequisite_outcome,
            args.finalize_build_outcome,
            args.finalize_runtime_outcome,
        )
        if any(finalize_outcomes):
            if not all(finalize_outcomes) or args.write_failure_result:
                raise ValueError("all finalize outcomes are required and cannot be combined with failure writing")
            finalize_workflow_evidence(
                args.manifest,
                prerequisite_outcome=args.finalize_prerequisite_outcome,
                build_outcome=args.finalize_build_outcome,
                runtime_outcome=args.finalize_runtime_outcome,
                source_sha=args.expected_source_sha,
                run_id=args.expected_run_id,
                run_attempt=args.expected_run_attempt,
            )
            return 0
        if args.write_failure_result:
            if not args.reason_code:
                raise ValueError("--reason-code is required when writing failure evidence")
            manifest = failure_manifest(
                result=args.write_failure_result,
                reason_code=args.reason_code,
                cleanup_verified=args.cleanup_verified,
                source_sha=args.expected_source_sha,
                run_id=args.expected_run_id,
                run_attempt=args.expected_run_attempt,
            )
            write_failure_manifest(args.manifest, manifest)
            return 0
        manifest = validate(
            args.manifest,
            expected_source_sha=args.expected_source_sha,
            expected_run_id=args.expected_run_id,
            expected_run_attempt=args.expected_run_attempt,
        )
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"SO_BINDTODEVICE evidence validation failed: {error}", file=sys.stderr)
        return 1
    if manifest["result"] != "PASS":
        print(
            "Validated classified SO_BINDTODEVICE failure: "
            f"{manifest['result']}/{manifest['reasonCode']}.",
            file=sys.stderr,
        )
        return 1
    print(f"Validated {len(PHASES)} physical SO_BINDTODEVICE/TUN phases.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
