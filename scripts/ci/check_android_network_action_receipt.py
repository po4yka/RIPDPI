#!/usr/bin/env python3
"""Validate source-owned Android network-evidence action receipts fail closed."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import stat
import sys
from pathlib import Path
from typing import Any, NamedTuple

VERSION = "android_network_evidence_action_receipt_v3"
REGISTRY_VERSION = "android_network_evidence_actions_v2"
ROOT = Path(__file__).resolve().parents[2]
REGISTRY_PATH = ROOT / "quality/release-gates/android-network-evidence-actions.json"
TEST_REGISTRY_DIRECTORY = ROOT / "scripts/tests/fixtures"
TEST_REGISTRY_VERSION = "android_network_evidence_test_ready_override_v1"
TEST_REGISTRY_OVERRIDE_PATH = (
    TEST_REGISTRY_DIRECTORY / "android-network-evidence-test-ready-override.json"
)
POLICY_PATH = ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json"
GATE_ID = "killswitch-tun-establish-native-ready"
KIND = "direct_window"
SELECTOR = (
    "com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest"
    "#vpnStartupWindowHoldsDnsPacketUntilNativeReady"
)
MARKER_DOMAIN = "ripdpi:network-evidence-marker:v2"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
IDENTIFIER_RE = re.compile(r"[a-z0-9][a-z0-9_-]{0,127}\Z")

COMMON_FIELDS = {
    "version",
    "status",
    "gateId",
    "kind",
    "selector",
    "semanticRule",
    "correlationId",
    "sourceSha",
    "clientArtifactSha256",
    "testArtifactSha256",
    "fixtureIdentitySha256",
    "actionMarkerSha256",
    "outcomeMarkerSha256",
    "querySetSha256",
    "startedAtElapsedRealtimeMs",
    "finishedAtElapsedRealtimeMs",
    "actionMarkerAtElapsedRealtimeMs",
    "outcomeMarkerAtElapsedRealtimeMs",
    "appUid",
    "testUid",
    "actionMarkerPid",
    "actionMarkerUid",
    "outcomeMarkerPid",
    "outcomeMarkerUid",
    "dnsProbePids",
    "dnsProbeUid",
    "facts",
}


class ActionDescriptor(NamedTuple):
    gate_id: str
    kind: str
    selector: str
    receipt_file: str
    semantic_rule: str
    production_ready: bool


class ReceiptError(ValueError):
    pass


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReceiptError(f"receipt contains duplicate JSON key: {key}")
        result[key] = value
    return result


def _strict_registry_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_bytes(), object_pairs_hook=reject_duplicate_keys)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ReceiptError(f"action registry is unreadable: {error}") from error
    if not isinstance(value, dict) or set(value) != {"version", "actions"}:
        raise ReceiptError("action registry top-level fields mismatch")
    if value["version"] != REGISTRY_VERSION or not isinstance(value["actions"], list):
        raise ReceiptError("action registry version or actions is invalid")
    return value


def load_action_registry(
    path: Path = REGISTRY_PATH,
    *,
    test_only_ready_override: Path | None = None,
) -> dict[str, ActionDescriptor]:
    value = _strict_registry_json(path)
    descriptors: dict[str, ActionDescriptor] = {}
    selectors: set[str] = set()
    receipt_files: set[str] = set()
    for index, raw in enumerate(value["actions"]):
        if not isinstance(raw, dict):
            raise ReceiptError(f"actions[{index}] must be an object")
        required = {
            "gateId",
            "kind",
            "selector",
            "receiptVersion",
            "receiptFile",
            "semanticRule",
            "windowPolicy",
            "productionReady",
            "blockingReasons",
        }
        if set(raw) != required:
            raise ReceiptError(f"actions[{index}] fields mismatch")
        gate_id = raw["gateId"]
        kind = raw["kind"]
        selector = raw["selector"]
        receipt_file = raw["receiptFile"]
        rule = raw["semanticRule"]
        if not isinstance(gate_id, str) or IDENTIFIER_RE.fullmatch(gate_id) is None:
            raise ReceiptError(f"actions[{index}].gateId is malformed")
        if kind not in {"dns", "direct_window"}:
            raise ReceiptError(f"actions[{index}].kind is unsupported")
        if not isinstance(selector, str) or selector.count("#") != 1:
            raise ReceiptError(f"actions[{index}].selector is malformed")
        if raw["receiptVersion"] != VERSION:
            raise ReceiptError(f"actions[{index}].receiptVersion mismatch")
        if (
            not isinstance(receipt_file, str)
            or receipt_file != f"network-evidence-action-receipt-{gate_id}.json"
        ):
            raise ReceiptError(f"actions[{index}].receiptFile is not gate-bound")
        if not isinstance(rule, str) or IDENTIFIER_RE.fullmatch(rule) is None:
            raise ReceiptError(f"actions[{index}].semanticRule is malformed")
        if not isinstance(raw["windowPolicy"], str) or not raw["windowPolicy"]:
            raise ReceiptError(f"actions[{index}].windowPolicy is malformed")
        if not isinstance(raw["productionReady"], bool):
            raise ReceiptError(f"actions[{index}].productionReady must be a boolean")
        if not isinstance(raw["blockingReasons"], list) or any(
            not isinstance(item, str) or not item for item in raw["blockingReasons"]
        ):
            raise ReceiptError(f"actions[{index}].blockingReasons is malformed")
        # The guard that rejected every ready action is gone, but nothing weakens:
        # a ready action must have no blockers left to declare, and the
        # selector-existence test still refuses a ready action whose
        # instrumentation method does not exist, so the registry cannot claim
        # coverage it does not have.
        if raw["productionReady"]:
            if raw["blockingReasons"]:
                raise ReceiptError(
                    f"actions[{index}] is production ready but still declares blockers"
                )
        elif len(raw["blockingReasons"]) < 2:
            raise ReceiptError(f"actions[{index}].blockingReasons is incomplete")
        if (
            gate_id in descriptors
            or selector in selectors
            or receipt_file in receipt_files
        ):
            raise ReceiptError(
                "action registry gate, selector, and receipt file must be unique"
            )
        descriptors[gate_id] = ActionDescriptor(
            gate_id, kind, selector, receipt_file, rule, raw["productionReady"]
        )
        selectors.add(selector)
        receipt_files.add(receipt_file)
    try:
        policy = json.loads(POLICY_PATH.read_text(encoding="utf-8"))
        policy_gate_ids = {
            gate["id"]
            for gate in policy["gates"]
            if gate.get("evidenceSources", {}).get("android-client-release")
            == "dual-vantage-network-manifest"
        }
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise ReceiptError(f"DNS evidence policy is unreadable: {error}") from error
    policy_gate_ids.add(GATE_ID)
    if set(descriptors) != policy_gate_ids:
        raise ReceiptError(
            "action registry does not exactly cover policy dual-vantage gates"
        )
    if test_only_ready_override is not None:
        override_path = test_only_ready_override.resolve()
        if override_path != TEST_REGISTRY_OVERRIDE_PATH.resolve():
            raise ReceiptError(
                "test-only readiness override must be a source-owned test fixture"
            )
        try:
            override = json.loads(
                override_path.read_bytes(), object_pairs_hook=reject_duplicate_keys
            )
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ReceiptError(
                f"test-only readiness override is unreadable: {error}"
            ) from error
        if (
            not isinstance(override, dict)
            or set(override) != {"version", "readyGateIds"}
            or override["version"] != TEST_REGISTRY_VERSION
            or not isinstance(override["readyGateIds"], list)
            or set(override["readyGateIds"]) != set(descriptors)
            or len(override["readyGateIds"]) != len(descriptors)
        ):
            raise ReceiptError(
                "test-only readiness override does not exactly cover the registry"
            )
        descriptors = {
            gate_id: descriptor._replace(production_ready=True)
            for gate_id, descriptor in descriptors.items()
        }
    return descriptors


def require_digest(value: Any, pattern: re.Pattern[str], field: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ReceiptError(f"{field} is malformed")
    return value


def require_int(value: Any, field: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ReceiptError(f"{field} must be an integer >= {minimum}")
    return value


def marker_sha256(
    correlation_id: str,
    phase: str,
    *,
    gate_id: str = GATE_ID,
    kind: str = KIND,
) -> str:
    preimage = f"{MARKER_DOMAIN}:{correlation_id}:{gate_id}:{kind}:{phase}"
    return hashlib.sha256(preimage.encode("ascii")).hexdigest()


def _digest(label: str) -> str:
    return hashlib.sha256(label.encode("ascii")).hexdigest()


def example_valid_facts(gate_id: str) -> dict[str, Any]:
    """Return production-shaped literals used by offline contract tests only."""

    def d(field: str) -> str:
        return _digest(f"{gate_id}:{field}")

    examples: dict[str, dict[str, Any]] = {
        GATE_ID: {
            "tunFd": 41,
            "closedWindowRunningCount": 0,
            "preReadyDnsEventCount": 0,
            "startupWindowAssertionElapsedMs": 300,
            "dnsRcode": 0,
            "dnsQuerySha256": d("dnsQuery"),
            "dnsAnswersExact": True,
            "postReadyDnsEventCount": 1,
            "txPackets": 2,
            "rxPackets": 1,
            "finalStatus": "Halted",
        },
        "dns-virtual-vpn-resolver": {
            "virtualQuerySha256": d("virtualQuery"),
            "virtualDnsAddressSha256": d("virtualDnsAddress"),
            "tunnelResolverProviderSha256": d("provider"),
            "fixtureTranscriptSha256": d("fixtureTranscript"),
            "resolverPath": "tunnel",
            "responseCount": 1,
            "tunnelEstablished": True,
        },
        "dns-proxied-through-tunnelled-resolver": {
            "proxiedQuerySha256": d("proxiedQuery"),
            "resolverProviderSha256": d("provider"),
            "fixtureTranscriptSha256": d("fixtureTranscript"),
            "socksTargetSha256": d("socksTarget"),
            "routeClass": "proxy",
            "relayDnsRoute": "socks5",
            "relayDnsFailClosed": True,
            "responseCount": 1,
            "tunnelEstablished": True,
        },
        "dns-direct-ru-only-for-direct-domains": {
            "directQuerySha256": d("directQuery"),
            "proxiedQuerySha256": d("proxiedQuery"),
            "directResolverSha256": d("directResolver"),
            "tunnelResolverSha256": d("tunnelResolver"),
            "directQueryCount": 1,
            "proxiedTunnelQueryCount": 1,
            "proxiedViaDirectCount": 0,
        },
        "dns-allowlisted-bootstrap-resolution": {
            "bootstrapQuerySha256": d("bootstrapQuery"),
            "allowlistedResolverSetSha256": d("allowlistedResolvers"),
            "observedBootstrapResolverSha256": d("observedResolver"),
            "bootstrapQueryCount": 1,
            "nonAllowlistedBootstrapCount": 0,
            "observedResolverAllowlisted": True,
        },
        "dns-no-isp-fallback-on-encrypted-resolver-outage": {
            "outageQuerySha256": d("outageQuery"),
            "encryptedResolverProviderSha256": d("provider"),
            "fixtureTranscriptSha256": d("fixtureTranscript"),
            "faultControlSha256": d("faultControl"),
            "faultObserved": True,
            "encryptedAttemptCount": 1,
            "plainFallbackCount": 0,
            "successfulResponseCount": 0,
        },
        "dns-network-switch-behavior": {
            "preSwitchQuerySha256": d("preSwitchQuery"),
            "duringSwitchQuerySha256": d("duringSwitchQuery"),
            "postSwitchQuerySha256": d("postSwitchQuery"),
            "preNetworkSha256": d("preNetwork"),
            "postNetworkSha256": d("postNetwork"),
            "transitionObserved": True,
            "underlayChangeCount": 1,
            "preTunnelResponseCount": 1,
            "postTunnelResponseCount": 1,
            "plainFallbackCount": 0,
        },
        "dns-core-crash-behavior": {
            "crashQuerySha256": d("crashQuery"),
            "encryptedResolverProviderSha256": d("provider"),
            "faultControlSha256": d("faultControl"),
            "corePid": 401,
            "coreExitCode": 19,
            "crashObserved": True,
            "encryptedControlCount": 1,
            "plainFallbackCount": 0,
            "successfulResponseAfterCrashCount": 0,
        },
        "dns-android-private-dns-conflict": {
            "conflictQuerySha256": d("conflictQuery"),
            "privateDnsProviderSha256": d("provider"),
            "privateDnsModeSha256": d("mode"),
            "conflictObserved": True,
            "encryptedControlCount": 1,
            "plainBypassCount": 0,
            "successfulBypassResponseCount": 0,
        },
        "synthetic-authoritative-dns-query-sources": {
            "proxyQuerySha256": d("proxyQuery"),
            "directQuerySha256": d("directQuery"),
            "ipv6QuerySha256": d("ipv6Query"),
            "proxySourceClass": "proxy-egress",
            "directSourceClass": "client-underlay-ipv4",
            "ipv6SourceClass": "client-underlay-ipv6",
            "authoritativeResponseCount": 3,
        },
    }
    try:
        return examples[gate_id]
    except KeyError as error:
        raise ReceiptError(f"unsupported gate ID: {gate_id}") from error


def query_set_sha256(gate_id: str, facts: dict[str, Any]) -> str:
    query_fields = sorted(
        (name, value)
        for name, value in facts.items()
        if name.lower().endswith("querysha256")
    )
    if not query_fields:
        raise ReceiptError("facts do not bind an exact query set")
    for name, value in query_fields:
        require_digest(value, SHA256_RE, f"facts.{name}")
    encoded = json.dumps(
        {"gateId": gate_id, "queries": query_fields},
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=True,
    ).encode("ascii")
    return hashlib.sha256(
        b"ripdpi:network-evidence-query-set:v1:" + encoded
    ).hexdigest()


def _validate_facts(gate_id: str, value: Any) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReceiptError("facts must be an object")
    expected = example_valid_facts(gate_id)
    if set(value) != set(expected):
        raise ReceiptError(
            f"facts fields mismatch: missing={sorted(set(expected) - set(value))}, "
            f"unknown={sorted(set(value) - set(expected))}"
        )
    for field, example in expected.items():
        actual = value[field]
        if field.endswith("Sha256"):
            require_digest(actual, SHA256_RE, f"facts.{field}")
        elif isinstance(example, bool):
            if actual is not example:
                raise ReceiptError(f"facts.{field} does not prove the required state")
        elif isinstance(example, int):
            minimum = 0 if field == "tunFd" else (1 if example > 0 else 0)
            actual_int = require_int(actual, f"facts.{field}", minimum=minimum)
            if example == 0 and actual_int != 0:
                raise ReceiptError(f"facts.{field} must equal zero")
            if field == "startupWindowAssertionElapsedMs" and actual_int >= 4_000:
                raise ReceiptError(
                    "facts.startupWindowAssertionElapsedMs exceeded the fail-closed budget"
                )
            if (
                field.endswith("Count")
                and field != "underlayChangeCount"
                and actual_int != example
            ):
                raise ReceiptError(f"facts.{field} must equal {example}")
            if field == "coreExitCode" and actual_int == 0:
                raise ReceiptError("facts.coreExitCode must prove a crash")
        elif actual != example:
            raise ReceiptError(f"facts.{field} does not match the source-owned value")
    query_digests = [
        value[name] for name in value if name.lower().endswith("querysha256")
    ]
    if len(query_digests) != len(set(query_digests)):
        raise ReceiptError("facts query digests must be unique")
    return value


def validate_receipt(
    value: Any,
    *,
    source_sha: str,
    correlation_id: str,
    client_artifact_sha256: str,
    test_artifact_sha256: str,
    fixture_identity_sha256: str,
    gate_id: str = GATE_ID,
    test_only_ready_override: Path | None = None,
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReceiptError("receipt must be a JSON object")
    if set(value) != COMMON_FIELDS:
        raise ReceiptError(
            f"receipt fields mismatch: missing={sorted(COMMON_FIELDS - set(value))}, "
            f"unknown={sorted(set(value) - COMMON_FIELDS)}"
        )
    registry = load_action_registry(test_only_ready_override=test_only_ready_override)
    descriptor = registry.get(gate_id)
    if descriptor is None:
        raise ReceiptError(f"unsupported gate ID: {gate_id}")
    if not descriptor.production_ready:
        raise ReceiptError(f"action descriptor is not production ready: {gate_id}")
    expected_literals = {
        "version": VERSION,
        "status": "PASS",
        "gateId": descriptor.gate_id,
        "kind": descriptor.kind,
        "selector": descriptor.selector,
        "semanticRule": descriptor.semantic_rule,
        "sourceSha": source_sha,
        "correlationId": correlation_id,
        "clientArtifactSha256": client_artifact_sha256,
        "testArtifactSha256": test_artifact_sha256,
        "fixtureIdentitySha256": fixture_identity_sha256,
    }
    for field, expected in expected_literals.items():
        if value[field] != expected or type(value[field]) is not type(expected):
            raise ReceiptError(
                f"{field} does not match the expected descriptor or provenance"
            )
    action = require_digest(
        value["actionMarkerSha256"], SHA256_RE, "actionMarkerSha256"
    )
    outcome = require_digest(
        value["outcomeMarkerSha256"], SHA256_RE, "outcomeMarkerSha256"
    )
    if action != marker_sha256(
        correlation_id, "action", gate_id=gate_id, kind=descriptor.kind
    ):
        raise ReceiptError("actionMarkerSha256 is not descriptor-derived")
    if outcome != marker_sha256(
        correlation_id, "outcome", gate_id=gate_id, kind=descriptor.kind
    ):
        raise ReceiptError("outcomeMarkerSha256 is not descriptor-derived")
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
    app_uid = require_int(value["appUid"], "appUid", minimum=1)
    test_uid = require_int(value["testUid"], "testUid", minimum=1)
    if app_uid == test_uid:
        raise ReceiptError("appUid and testUid must differ")
    if require_int(value["actionMarkerUid"], "actionMarkerUid", minimum=1) != app_uid:
        raise ReceiptError("action marker UID is not the target app UID")
    if require_int(value["outcomeMarkerUid"], "outcomeMarkerUid", minimum=1) != app_uid:
        raise ReceiptError("outcome marker UID is not the target app UID")
    if require_int(value["dnsProbeUid"], "dnsProbeUid", minimum=1) != test_uid:
        raise ReceiptError("DNS probe UID is not the androidTest UID")
    require_int(value["actionMarkerPid"], "actionMarkerPid", minimum=1)
    require_int(value["outcomeMarkerPid"], "outcomeMarkerPid", minimum=1)
    pids = value["dnsProbePids"]
    if not isinstance(pids, list) or not pids or len(pids) != len(set(pids)):
        raise ReceiptError("dnsProbePids must be a non-empty unique array")
    for index, pid in enumerate(pids):
        require_int(pid, f"dnsProbePids[{index}]", minimum=1)
    facts = _validate_facts(gate_id, value["facts"])
    if require_digest(
        value["querySetSha256"], SHA256_RE, "querySetSha256"
    ) != query_set_sha256(gate_id, facts):
        raise ReceiptError("querySetSha256 does not bind the exact receipt query set")
    return value


def load_private_receipt(path: Path) -> tuple[dict[str, Any], bytes]:
    if not path.is_absolute():
        raise ReceiptError("receipt path must be absolute")
    flags = os.O_RDONLY | os.O_CLOEXEC
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags)
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
    parser.add_argument("--gate-id", required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--correlation-id", required=True)
    parser.add_argument("--client-artifact-sha256", required=True)
    parser.add_argument("--test-artifact-sha256", required=True)
    parser.add_argument("--fixture-identity-sha256", required=True)
    parser.add_argument("--test-only-ready-override", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        expected = {
            "gate_id": args.gate_id,
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
        validate_receipt(
            value,
            **expected,
            test_only_ready_override=args.test_only_ready_override,
        )
    except (OSError, ReceiptError) as error:
        print(f"Android network action receipt: {error}", file=sys.stderr)
        return 1
    print(hashlib.sha256(raw).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
