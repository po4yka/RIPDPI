#!/usr/bin/env python3
"""Validate a private Android network-evidence action receipt fail closed."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path
from typing import Any

VERSION = "android_network_evidence_action_receipt_v1"
GATE_ID = "killswitch-tun-establish-native-ready"
KIND = "direct_window"
SELECTOR = (
    "com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest"
    "#vpnStartupWindowHoldsDnsPacketUntilNativeReady"
)
MARKER_DOMAIN = "ripdpi:network-evidence-marker:v2"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
FIELDS = {
    "version",
    "status",
    "gateId",
    "kind",
    "selector",
    "correlationId",
    "sourceSha",
    "clientArtifactSha256",
    "testArtifactSha256",
    "fixtureIdentitySha256",
    "actionMarkerSha256",
    "outcomeMarkerSha256",
    "startedAtElapsedRealtimeMs",
    "finishedAtElapsedRealtimeMs",
    "actionMarkerAtElapsedRealtimeMs",
    "outcomeMarkerAtElapsedRealtimeMs",
    "appAndTestUidsDistinct",
    "actionMarkerRanAsTargetApp",
    "outcomeMarkerRanAsTargetApp",
    "dnsProbeRanAsAndroidTest",
    "actionMarkerPidObserved",
    "outcomeMarkerPidObserved",
    "dnsProbePidObserved",
    "tunFdObserved",
    "closedWindowRunningCount",
    "preReadyDnsEventCount",
    "startupWindowAssertionElapsedMs",
    "dnsRcode",
    "dnsAnswersExact",
    "postReadyDnsEventCount",
    "txPackets",
    "rxPackets",
    "finalStatus",
    "gateClean",
}


class ReceiptError(ValueError):
    pass


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReceiptError(f"receipt contains duplicate JSON key: {key}")
        result[key] = value
    return result


def require_digest(value: Any, pattern: re.Pattern[str], field: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ReceiptError(f"{field} is malformed")
    return value


def require_int(value: Any, field: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ReceiptError(f"{field} must be an integer >= {minimum}")
    return value


def marker_sha256(correlation_id: str, phase: str) -> str:
    preimage = f"{MARKER_DOMAIN}:{correlation_id}:{GATE_ID}:{KIND}:{phase}"
    return hashlib.sha256(preimage.encode("ascii")).hexdigest()


def validate_receipt(
    value: Any,
    *,
    source_sha: str,
    correlation_id: str,
    client_artifact_sha256: str,
    test_artifact_sha256: str,
    fixture_identity_sha256: str,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReceiptError("receipt must be a JSON object")
    if set(value) != FIELDS:
        missing = sorted(FIELDS - set(value))
        unknown = sorted(set(value) - FIELDS)
        raise ReceiptError(
            f"receipt fields mismatch: missing={missing}, unknown={unknown}"
        )
    expected_literals = {
        "version": VERSION,
        "status": "PASS",
        "gateId": GATE_ID,
        "kind": KIND,
        "selector": SELECTOR,
        "sourceSha": source_sha,
        "correlationId": correlation_id,
        "clientArtifactSha256": client_artifact_sha256,
        "testArtifactSha256": test_artifact_sha256,
        "fixtureIdentitySha256": fixture_identity_sha256,
        "finalStatus": "Halted",
        "gateClean": True,
        "dnsAnswersExact": True,
        "appAndTestUidsDistinct": True,
        "actionMarkerRanAsTargetApp": True,
        "outcomeMarkerRanAsTargetApp": True,
        "dnsProbeRanAsAndroidTest": True,
        "actionMarkerPidObserved": True,
        "outcomeMarkerPidObserved": True,
        "dnsProbePidObserved": True,
        "tunFdObserved": True,
    }
    for field, expected in expected_literals.items():
        if value[field] != expected or type(value[field]) is not type(expected):
            raise ReceiptError(f"{field} does not match the expected value")

    action = require_digest(
        value["actionMarkerSha256"], SHA256_RE, "actionMarkerSha256"
    )
    outcome = require_digest(
        value["outcomeMarkerSha256"], SHA256_RE, "outcomeMarkerSha256"
    )
    if action != marker_sha256(correlation_id, "action"):
        raise ReceiptError("actionMarkerSha256 is not source-derived")
    if outcome != marker_sha256(correlation_id, "outcome"):
        raise ReceiptError("outcomeMarkerSha256 is not source-derived")
    if action == outcome:
        raise ReceiptError("action and outcome markers must differ")

    started = require_int(
        value["startedAtElapsedRealtimeMs"], "startedAtElapsedRealtimeMs", minimum=1
    )
    action_at = require_int(
        value["actionMarkerAtElapsedRealtimeMs"],
        "actionMarkerAtElapsedRealtimeMs",
        minimum=1,
    )
    outcome_at = require_int(
        value["outcomeMarkerAtElapsedRealtimeMs"],
        "outcomeMarkerAtElapsedRealtimeMs",
        minimum=1,
    )
    finished = require_int(
        value["finishedAtElapsedRealtimeMs"], "finishedAtElapsedRealtimeMs", minimum=1
    )
    if not started <= action_at < outcome_at <= finished:
        raise ReceiptError("receipt elapsed-realtime ordering is invalid")

    exact_counts = {
        "closedWindowRunningCount": 0,
        "preReadyDnsEventCount": 0,
        "dnsRcode": 0,
        "postReadyDnsEventCount": 1,
    }
    for field, expected in exact_counts.items():
        if require_int(value[field], field) != expected:
            raise ReceiptError(f"{field} must equal {expected}")
    startup_elapsed = require_int(
        value["startupWindowAssertionElapsedMs"],
        "startupWindowAssertionElapsedMs",
        minimum=1,
    )
    if startup_elapsed >= 4_000:
        raise ReceiptError(
            "startupWindowAssertionElapsedMs exceeded the fail-closed budget"
        )
    for field in ("txPackets", "rxPackets"):
        require_int(value[field], field, minimum=1)
    return value


def load_private_receipt(path: Path) -> tuple[dict[str, Any], bytes]:
    if not path.is_absolute():
        raise ReceiptError("receipt path must be absolute")
    descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise ReceiptError("receipt must be a regular file")
        if stat.S_IMODE(metadata.st_mode) != 0o600:
            raise ReceiptError("receipt mode must be 0600")
        if metadata.st_size <= 0 or metadata.st_size > 64 * 1024:
            raise ReceiptError("receipt size is outside the accepted bound")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            raw = source.read(64 * 1024 + 1)
    finally:
        os.close(descriptor)
    if len(raw) != metadata.st_size:
        raise ReceiptError("receipt changed while it was read")
    try:
        decoded = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReceiptError(f"receipt is not valid UTF-8 JSON: {error}") from error
    return decoded, raw


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("receipt", type=Path)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--correlation-id", required=True)
    parser.add_argument("--client-artifact-sha256", required=True)
    parser.add_argument("--test-artifact-sha256", required=True)
    parser.add_argument("--fixture-identity-sha256", required=True)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        expected = {
            "source_sha": require_digest(args.source_sha, SHA1_RE, "sourceSha"),
            "correlation_id": require_digest(
                args.correlation_id, SHA256_RE, "correlationId"
            ),
            "client_artifact_sha256": require_digest(
                args.client_artifact_sha256, SHA256_RE, "clientArtifactSha256"
            ),
            "test_artifact_sha256": require_digest(
                args.test_artifact_sha256, SHA256_RE, "testArtifactSha256"
            ),
            "fixture_identity_sha256": require_digest(
                args.fixture_identity_sha256, SHA256_RE, "fixtureIdentitySha256"
            ),
        }
        if expected["client_artifact_sha256"] == expected["test_artifact_sha256"]:
            raise ReceiptError("client and androidTest artifact digests must differ")
        value, raw = load_private_receipt(args.receipt)
        validate_receipt(value, **expected)
    except (OSError, ReceiptError) as error:
        print(f"Android network action receipt: {error}", file=sys.stderr)
        return 1
    print(hashlib.sha256(raw).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
