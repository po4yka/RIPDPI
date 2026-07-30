#!/usr/bin/env python3
"""Derive redacted observations from two source-owned classic PCAP captures.

The action ledger, action receipt, fixture manifest, and raw captures are
private inputs. Generic marker pairs are accepted only for non-policy fixture
windows. Android policy windows require an explicit gate-specific rule.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import ipaddress
import json
import os
import re
import stat
import struct
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator


LEDGER_VERSION = "network_evidence_action_ledger_v2"
PLAN_VERSION = "network_evidence_scenario_plan_v3"
OBSERVATION_VERSION = "network_evidence_observation_v3"
ROLES = ("client-underlay", "external-observer")
ROOT = Path(__file__).resolve().parents[2]
SOURCE_POLICY_PATH = ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json"
ANDROID_RELEASE_SCOPE = "android-client-release"
WIRE_MARKER_PREFIX = "RIPDPI-EVIDENCE-V2:"
SHA1_RE = re.compile(r"[0-9a-f]{40}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
WIRE_MARKER_RE = re.compile(rb"RIPDPI-EVIDENCE-V2:([0-9a-f]{64})")
IDENTIFIER_RE = re.compile(r"[a-z0-9][a-z0-9_-]{0,127}")
ALLOWED_KINDS = {"dns", "ipv6", "direct_window"}
MAX_CAPTURE_BYTES = 64 * 1024 * 1024
MAX_PACKET_BYTES = 65535
MAX_ORIGINAL_PACKET_BYTES = 16 * 1024 * 1024
MAX_MARKER_BYTES = 512
MAX_PACKETS = 1_000_000
MAX_LEDGER_BYTES = 1024 * 1024
MAX_METADATA_BYTES = 64 * 1024
MAX_TRANSCRIPT_BYTES = 1024 * 1024
MAX_WINDOWS = 64
STARTUP_GATE_ID = "killswitch-tun-establish-native-ready"
STARTUP_RULE = "tun-establish-native-ready-v1"
DNS_RULES = {
    "dns-virtual-vpn-resolver": "virtual-vpn-resolver-v1",
    "dns-proxied-through-tunnelled-resolver": "proxied-domain-resolver-path-v1",
    "dns-allowlisted-bootstrap-resolution": "allowlisted-bootstrap-v1",
    "dns-no-isp-fallback-on-encrypted-resolver-outage": "encrypted-outage-fail-closed-v1",
    "dns-network-switch-behavior": "network-switch-dns-continuity-v1",
    "dns-core-crash-behavior": "core-crash-dns-fail-closed-v1",
    "dns-android-private-dns-conflict": "android-private-dns-conflict-v1",
}
SOURCE_OWNED_ENCRYPTED_DNS_RULES = {
    "virtual-vpn-resolver-v1",
    "proxied-domain-resolver-path-v1",
    "encrypted-outage-fail-closed-v1",
}
SOURCE_OWNED_AUTHORITATIVE_IPV6 = ipaddress.ip_address("2001:db8::20").packed
FIXTURE_IDENTITY_DOMAIN = "ripdpi:fixture-identity:v1"
FIXTURE_IDENTITY_FIELDS = (
    "bindHost",
    "androidHost",
    "tcpEchoPort",
    "udpEchoPort",
    "tlsEchoPort",
    "dnsUdpPort",
    "dnsHttpPort",
    "dnsDotPort",
    "dnsDnscryptPort",
    "dnsDoqPort",
    "socks5Port",
    "controlPort",
    "fixtureDomain",
    "fixtureIpv4",
    "dnsAnswerIpv4",
    "tlsCertificatePem",
    "dnscryptProviderName",
    "dnscryptPublicKey",
)

LEDGER_FIELDS = {"version", "scenarioPlan", "semanticRules", "captures"}
PLAN_FIELDS = {
    "version",
    "sourceSha",
    "correlationId",
    "clientArtifactSha256",
    "testArtifactSha256",
    "windows",
}
WINDOW_FIELDS = {
    "id",
    "kind",
    "startedAtEpoch",
    "finishedAtEpoch",
    "actionMarkerSha256",
    "outcomeMarkerSha256",
}
GENERIC_RULE_FIELDS = {"windowId", "rule"}
STARTUP_RULE_FIELDS = {
    "windowId",
    "rule",
    "actionReceiptSha256",
    "fixtureIdentitySha256",
}
ACTION_RULE_FIELDS = STARTUP_RULE_FIELDS
CAPTURE_FIELDS = {"rawCaptureSha256"}
METADATA_FIELDS = {
    "version",
    "role",
    "correlationId",
    "captureStartedAtEpoch",
    "captureFinishedAtEpoch",
    "packetCount",
    "rawCaptureSha256",
}


@dataclass(frozen=True)
class Window:
    identifier: str
    kind: str
    started: int
    finished: int
    action: bytes
    outcome: bytes
    action_sha256: str
    outcome_sha256: str


@dataclass(frozen=True)
class Packet:
    timestamp_ns: int
    payload: bytes | None
    ip_version: int | None = None
    transport: str | None = None
    source_address: bytes | None = None
    destination_address: bytes | None = None
    source_port: int | None = None
    destination_port: int | None = None
    tcp_sequence: int | None = None


@dataclass(frozen=True)
class SemanticRule:
    window_id: str
    name: str
    action_receipt_sha256: str | None = None
    fixture_identity_sha256: str | None = None


@dataclass(frozen=True)
class FixtureContext:
    identity_sha256: str
    address: bytes
    control_port: int
    dns_udp_port: int
    dns_dot_port: int
    socks5_port: int
    fixture_domain_labels: tuple[bytes, ...]
    dns_answer_address: bytes


@dataclass(frozen=True)
class EncryptedDnsTranscriptProof:
    digest: bytes
    flow_peer: tuple[bytes, int]
    flow_target: tuple[bytes, int]
    query_created_at_ms: int
    query_wire_bytes: int
    tls_target: tuple[bytes, int]


@dataclass(frozen=True)
class EncryptedDnsRoleProof:
    transcript_digest: bytes
    source: tuple[bytes, int]
    target: tuple[bytes, int]
    outbound_tls_digest: bytes
    inbound_tls_digest: bytes
    packet_timestamps_ns: tuple[int, ...]
    normalized_flow_digest: bytes


@dataclass(frozen=True)
class TlsStreamProof:
    digest: bytes
    application_data_lengths: tuple[int, ...]


@dataclass(frozen=True)
class PacketFacts:
    payload: bytes
    ip_version: int
    transport: str
    source_address: bytes
    destination_address: bytes
    source_port: int
    destination_port: int
    tcp_sequence: int | None


@dataclass(frozen=True)
class DnsPacketFacts:
    identity: bytes
    question_labels: tuple[bytes, ...]
    is_response: bool
    rcode: int
    answer_records: tuple[tuple[tuple[bytes, ...], bytes], ...]


@dataclass(frozen=True)
class CaptureMetadata:
    role: str
    correlation_id: str
    started: int
    finished: int
    packet_count: int
    raw_capture_sha256: str


@dataclass(frozen=True)
class MarkerPosition:
    first_record_index: int
    last_record_index: int
    first_timestamp_ns: int
    last_timestamp_ns: int


def canonical_json_bytes(value: object) -> bytes:
    return (json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n").encode(
        "utf-8"
    )


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"JSON has duplicate field: {key}")
        result[key] = value
    return result


def load_strict_json(
    path: Path, context: str, *, maximum_bytes: int
) -> tuple[dict[str, Any], bytes]:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"{context} must be a regular non-symlink file")
    if path.stat().st_size > maximum_bytes:
        raise ValueError(f"{context} exceeds the size bound")
    raw = path.read_bytes()
    if len(raw) > maximum_bytes:
        raise ValueError(f"{context} exceeds the size bound")
    try:
        value = json.loads(raw, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{context} is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ValueError(f"{context} must be a JSON object")
    if raw != canonical_json_bytes(value):
        raise ValueError(f"{context} must use canonical JSON encoding")
    return value, raw


def _fixture_identity_sha256(value: dict[str, Any]) -> str:
    missing = sorted(set(FIXTURE_IDENTITY_FIELDS) - set(value))
    if missing:
        raise ValueError(f"fixture manifest is missing fields: {', '.join(missing)}")
    digest = hashlib.sha256()
    for item in (
        FIXTURE_IDENTITY_DOMAIN,
        *(
            part
            for field in FIXTURE_IDENTITY_FIELDS
            for part in (field, str(value[field]))
        ),
    ):
        encoded = item.encode("utf-8")
        digest.update(struct.pack("!I", len(encoded)))
        digest.update(encoded)
    return digest.hexdigest()


def load_fixture_context(path: Path, expected_identity: str) -> FixtureContext:
    if not path.is_absolute():
        raise ValueError("fixture manifest path must be absolute")
    descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or metadata.st_nlink != 1
            or stat.S_IMODE(metadata.st_mode) != 0o600
        ):
            raise ValueError(
                "fixture manifest must be a private caller-owned mode-0600 file"
            )
        if metadata.st_size <= 0 or metadata.st_size > MAX_METADATA_BYTES:
            raise ValueError("fixture manifest exceeds the size bound")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            raw = source.read(MAX_METADATA_BYTES + 1)
    finally:
        os.close(descriptor)
    if len(raw) != metadata.st_size:
        raise ValueError("fixture manifest changed while it was read")
    try:
        value = json.loads(raw, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("fixture manifest is not valid UTF-8 JSON") from error
    if not isinstance(value, dict):
        raise ValueError("fixture manifest must be a JSON object")
    identity = _fixture_identity_sha256(value)
    if identity != expected_identity:
        raise ValueError("fixture manifest identity digest mismatch")
    for field in (
        "tcpEchoPort",
        "udpEchoPort",
        "tlsEchoPort",
        "dnsUdpPort",
        "dnsHttpPort",
        "dnsDotPort",
        "dnsDnscryptPort",
        "dnsDoqPort",
        "socks5Port",
        "controlPort",
    ):
        port = value[field]
        if (
            isinstance(port, bool)
            or not isinstance(port, int)
            or not 1 <= port <= 65535
        ):
            raise ValueError(f"fixture manifest {field} must be a port")
    try:
        address = ipaddress.ip_address(value["androidHost"])
    except ValueError as error:
        raise ValueError(
            "fixture manifest androidHost must be a numeric address"
        ) from error
    if (
        address.is_unspecified
        or address.is_loopback
        or address.is_link_local
        or address.is_multicast
    ):
        raise ValueError(
            "fixture manifest androidHost must be a routed unicast address"
        )
    fixture_domain = value["fixtureDomain"]
    if not isinstance(fixture_domain, str):
        raise ValueError("fixture manifest fixtureDomain must be a string")
    try:
        fixture_domain_labels = tuple(
            label.encode("ascii").lower()
            for label in fixture_domain.rstrip(".").split(".")
        )
    except UnicodeEncodeError as error:
        raise ValueError("fixture manifest fixtureDomain must be ASCII") from error
    if not fixture_domain_labels or any(
        not label or len(label) > 63 for label in fixture_domain_labels
    ):
        raise ValueError("fixture manifest fixtureDomain is malformed")
    try:
        dns_answer = ipaddress.ip_address(value["dnsAnswerIpv4"])
    except ValueError as error:
        raise ValueError(
            "fixture manifest dnsAnswerIpv4 must be an IPv4 address"
        ) from error
    if dns_answer.version != 4:
        raise ValueError("fixture manifest dnsAnswerIpv4 must be an IPv4 address")
    return FixtureContext(
        identity_sha256=identity,
        address=address.packed,
        control_port=value["controlPort"],
        dns_udp_port=value["dnsUdpPort"],
        dns_dot_port=value["dnsDotPort"],
        socks5_port=value["socks5Port"],
        fixture_domain_labels=fixture_domain_labels,
        dns_answer_address=dns_answer.packed,
    )


def validate_action_receipt(
    path: Path,
    *,
    expected_digest: str,
    fixture_identity_sha256: str,
    plan: dict[str, Any],
    gate_id: str,
    test_only_ready_override: Path | None = None,
) -> tuple[str, dict[str, Any]]:
    if str(ROOT) not in sys.path:
        sys.path.insert(0, str(ROOT))
    from scripts.ci.check_android_network_action_receipt import (  # noqa: PLC0415
        ReceiptError,
        load_private_receipt,
        validate_receipt,
    )

    try:
        value, raw = load_private_receipt(path)
        if hashlib.sha256(raw).hexdigest() != expected_digest:
            raise ValueError("Android action receipt digest mismatch")
        validated = validate_receipt(
            value,
            gate_id=gate_id,
            source_sha=plan["sourceSha"],
            correlation_id=plan["correlationId"],
            client_artifact_sha256=plan["clientArtifactSha256"],
            test_artifact_sha256=plan["testArtifactSha256"],
            fixture_identity_sha256=fixture_identity_sha256,
            test_only_ready_override=test_only_ready_override,
        )
        return (
            require_pattern(
                validated["querySetSha256"], SHA256_RE, "receipt.querySetSha256"
            ),
            validated["facts"],
        )
    except ReceiptError as error:
        raise ValueError(f"Android action receipt is invalid: {error}") from error


def require_exact_fields(value: dict[str, Any], fields: set[str], context: str) -> None:
    missing = sorted(fields - set(value))
    unknown = sorted(set(value) - fields)
    if missing:
        raise ValueError(f"{context} is missing fields: {', '.join(missing)}")
    if unknown:
        raise ValueError(f"{context} has unknown fields: {', '.join(unknown)}")


def require_pattern(value: Any, pattern: re.Pattern[str], context: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f"{context} has invalid format")
    return value


def require_epoch(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"{context} must be a positive integer")
    return value


def require_count(value: Any, context: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{context} must be a non-negative integer")
    return value


def validate_capture_metadata(
    value: dict[str, Any], *, expected_role: str, expected_correlation_id: str
) -> CaptureMetadata:
    context = f"{expected_role} capture metadata"
    require_exact_fields(value, METADATA_FIELDS, context)
    if value["version"] != "network_evidence_private_capture_v1":
        raise ValueError(f"{context} has an unsupported version")
    if value["role"] != expected_role:
        raise ValueError(f"{context} role mismatch")
    correlation_id = require_pattern(
        value["correlationId"], SHA256_RE, f"{context}.correlationId"
    )
    if correlation_id != expected_correlation_id:
        raise ValueError(f"{context} correlationId mismatch")
    started = require_epoch(
        value["captureStartedAtEpoch"], f"{context}.captureStartedAtEpoch"
    )
    finished = require_epoch(
        value["captureFinishedAtEpoch"], f"{context}.captureFinishedAtEpoch"
    )
    if finished <= started:
        raise ValueError(f"{context} must have positive actual bounds")
    return CaptureMetadata(
        role=expected_role,
        correlation_id=correlation_id,
        started=started,
        finished=finished,
        packet_count=require_count(value["packetCount"], f"{context}.packetCount"),
        raw_capture_sha256=require_pattern(
            value["rawCaptureSha256"], SHA256_RE, f"{context}.rawCaptureSha256"
        ),
    )


def derive_marker_preimage(
    correlation_id: str, identifier: str, kind: str, phase: str
) -> bytes:
    preimage = (
        "ripdpi:network-evidence-marker:v2:"
        f"{correlation_id}:{identifier}:{kind}:{phase}"
    ).encode("ascii")
    if len(preimage) > MAX_MARKER_BYTES:
        raise ValueError("derived marker preimage exceeds the size bound")
    return preimage


def wire_marker(marker_sha256: str) -> bytes:
    marker = (WIRE_MARKER_PREFIX + marker_sha256).encode("ascii")
    if len(marker) > MAX_MARKER_BYTES:
        raise ValueError("wire marker exceeds the size bound")
    return marker


def load_android_dual_vantage_gate_ids() -> set[str]:
    if SOURCE_POLICY_PATH.is_symlink() or not SOURCE_POLICY_PATH.is_file():
        raise ValueError(
            "source release-gate policy must be a regular non-symlink file"
        )
    try:
        policy = json.loads(
            SOURCE_POLICY_PATH.read_bytes(), object_pairs_hook=_reject_duplicate_keys
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(
            "source release-gate policy is not valid UTF-8 JSON"
        ) from error
    if not isinstance(policy, dict):
        raise ValueError("source release-gate policy must be a JSON object")
    gates = policy.get("gates")
    policy_scopes = policy.get("appliesTo", [])
    if not isinstance(gates, list) or not isinstance(policy_scopes, list):
        raise ValueError("source release-gate policy has invalid gates or appliesTo")
    result: set[str] = set()
    for index, gate in enumerate(gates):
        if not isinstance(gate, dict):
            raise ValueError(
                f"source release-gate policy.gates[{index}] must be an object"
            )
        evidence_sources = gate.get("evidenceSources", {})
        if not isinstance(evidence_sources, dict):
            raise ValueError(
                f"source release-gate policy.gates[{index}].evidenceSources must be an object"
            )
        evidence_source = evidence_sources.get(
            ANDROID_RELEASE_SCOPE, gate.get("evidenceSource")
        )
        applies_to = gate.get("appliesTo", policy_scopes)
        if not isinstance(applies_to, list):
            raise ValueError(
                f"source release-gate policy.gates[{index}].appliesTo must be an array"
            )
        if (
            evidence_source == "dual-vantage-network-manifest"
            and ANDROID_RELEASE_SCOPE in applies_to
        ):
            result.add(
                require_pattern(
                    gate.get("id"),
                    IDENTIFIER_RE,
                    f"source release-gate policy.gates[{index}].id",
                )
            )
    if not result:
        raise ValueError("source policy has no Android dual-vantage gate IDs")
    return result


def validate_ledger(
    value: dict[str, Any], *, forbidden_generic_ids: set[str]
) -> tuple[dict[str, Any], list[Window], dict[str, str], dict[str, SemanticRule]]:
    require_exact_fields(value, LEDGER_FIELDS, "action ledger")
    if value["version"] != LEDGER_VERSION:
        raise ValueError("unexpected action ledger version")

    plan = value["scenarioPlan"]
    if not isinstance(plan, dict):
        raise ValueError("action ledger scenarioPlan must be an object")
    require_exact_fields(plan, PLAN_FIELDS, "scenario plan")
    if plan["version"] != PLAN_VERSION:
        raise ValueError("unexpected scenario plan version")
    correlation_id = require_pattern(plan["correlationId"], SHA256_RE, "correlationId")
    require_pattern(plan["sourceSha"], SHA1_RE, "sourceSha")
    require_pattern(plan["clientArtifactSha256"], SHA256_RE, "clientArtifactSha256")
    require_pattern(plan["testArtifactSha256"], SHA256_RE, "testArtifactSha256")

    raw_windows = plan["windows"]
    if not isinstance(raw_windows, list) or not raw_windows:
        raise ValueError("scenario plan windows must be a non-empty array")
    if len(raw_windows) > MAX_WINDOWS:
        raise ValueError("scenario plan exceeds the window count bound")
    windows: list[Window] = []
    seen_ids: set[str] = set()
    seen_markers: set[bytes] = set()
    for index, raw_window in enumerate(raw_windows):
        context = f"scenario plan.windows[{index}]"
        if not isinstance(raw_window, dict):
            raise ValueError(f"{context} must be an object")
        require_exact_fields(raw_window, WINDOW_FIELDS, context)
        identifier = require_pattern(raw_window["id"], IDENTIFIER_RE, f"{context}.id")
        kind = raw_window["kind"]
        if not isinstance(kind, str) or kind not in ALLOWED_KINDS:
            raise ValueError(f"{context}.kind is not allowed")
        if identifier in seen_ids:
            raise ValueError(f"scenario plan has duplicate window id: {identifier}")
        started = require_epoch(
            raw_window["startedAtEpoch"], f"{context}.startedAtEpoch"
        )
        finished = require_epoch(
            raw_window["finishedAtEpoch"], f"{context}.finishedAtEpoch"
        )
        if finished <= started:
            raise ValueError(f"{context} must have a positive duration")
        if windows and started < windows[-1].finished:
            raise ValueError(
                "scenario plan windows must be ordered and non-overlapping"
            )
        action_preimage = derive_marker_preimage(
            correlation_id, identifier, kind, "action"
        )
        outcome_preimage = derive_marker_preimage(
            correlation_id, identifier, kind, "outcome"
        )
        action_sha256 = require_pattern(
            raw_window["actionMarkerSha256"], SHA256_RE, f"{context}.actionMarkerSha256"
        )
        outcome_sha256 = require_pattern(
            raw_window["outcomeMarkerSha256"],
            SHA256_RE,
            f"{context}.outcomeMarkerSha256",
        )
        if hashlib.sha256(action_preimage).hexdigest() != action_sha256:
            raise ValueError(f"{context} action marker digest mismatch")
        if hashlib.sha256(outcome_preimage).hexdigest() != outcome_sha256:
            raise ValueError(f"{context} outcome marker digest mismatch")
        action = wire_marker(action_sha256)
        outcome = wire_marker(outcome_sha256)
        if action in seen_markers or outcome in seen_markers or action == outcome:
            raise ValueError("scenario plan markers must be globally unique")
        seen_ids.add(identifier)
        seen_markers.update((action, outcome))
        windows.append(
            Window(
                identifier,
                kind,
                started,
                finished,
                action,
                outcome,
                action_sha256,
                outcome_sha256,
            )
        )

    rules = value["semanticRules"]
    if not isinstance(rules, list):
        raise ValueError("semanticRules must be an array")
    semantic_rules: dict[str, SemanticRule] = {}
    for index, rule in enumerate(rules):
        context = f"semanticRules[{index}]"
        if not isinstance(rule, dict):
            raise ValueError(f"{context} must be an object")
        rule_name = rule.get("rule")
        fields = (
            ACTION_RULE_FIELDS
            if rule_name in ({STARTUP_RULE} | set(DNS_RULES.values()))
            else GENERIC_RULE_FIELDS
        )
        require_exact_fields(rule, fields, context)
        window_id = require_pattern(
            rule["windowId"], IDENTIFIER_RE, f"{context}.windowId"
        )
        if window_id in semantic_rules:
            raise ValueError(f"semanticRules has duplicate window id: {window_id}")
        window = next((item for item in windows if item.identifier == window_id), None)
        if window is None:
            raise ValueError(f"semanticRules references unknown window id: {window_id}")
        if rule_name == STARTUP_RULE:
            if window_id != STARTUP_GATE_ID or window.kind != "direct_window":
                raise ValueError(
                    f"{STARTUP_RULE} is restricted to the startup direct-window gate"
                )
            semantic_rules[window_id] = SemanticRule(
                window_id=window_id,
                name=rule_name,
                action_receipt_sha256=require_pattern(
                    rule["actionReceiptSha256"],
                    SHA256_RE,
                    f"{context}.actionReceiptSha256",
                ),
                fixture_identity_sha256=require_pattern(
                    rule["fixtureIdentitySha256"],
                    SHA256_RE,
                    f"{context}.fixtureIdentitySha256",
                ),
            )
            continue
        if rule_name in DNS_RULES.values():
            if DNS_RULES.get(window_id) != rule_name or window.kind != "dns":
                raise ValueError(
                    f"{rule_name} is not bound to its exact DNS gate descriptor"
                )
            semantic_rules[window_id] = SemanticRule(
                window_id=window_id,
                name=rule_name,
                action_receipt_sha256=require_pattern(
                    rule["actionReceiptSha256"],
                    SHA256_RE,
                    f"{context}.actionReceiptSha256",
                ),
                fixture_identity_sha256=require_pattern(
                    rule["fixtureIdentitySha256"],
                    SHA256_RE,
                    f"{context}.fixtureIdentitySha256",
                ),
            )
            continue
        if rule_name != "generic-marker-pair":
            raise ValueError(
                f"unsupported semantic rule for {window_id}; "
                "the ledger does not prove a source-owned gate semantic seam"
            )
        if window_id in forbidden_generic_ids:
            raise ValueError(
                f"generic-marker-pair is forbidden for Android dual-vantage gate {window_id}; "
                "a source-owned semantic action seam is required"
            )
        semantic_rules[window_id] = SemanticRule(window_id, rule_name)
    if set(semantic_rules) != seen_ids:
        raise ValueError("semanticRules must prove semantics for every window")

    captures = value["captures"]
    if not isinstance(captures, dict) or set(captures) != set(ROLES):
        raise ValueError("captures must contain exactly both required vantages")
    digests: dict[str, str] = {}
    for role in ROLES:
        capture = captures[role]
        if not isinstance(capture, dict):
            raise ValueError(f"captures.{role} must be an object")
        require_exact_fields(capture, CAPTURE_FIELDS, f"captures.{role}")
        digests[role] = require_pattern(
            capture["rawCaptureSha256"], SHA256_RE, f"captures.{role}.rawCaptureSha256"
        )
    if len(set(digests.values())) != len(ROLES):
        raise ValueError("dual-vantage captures must have distinct raw digests")
    return plan, windows, digests, semantic_rules


def _parse_ethernet_payload(frame: bytes) -> PacketFacts:
    if len(frame) < 14:
        raise ValueError("truncated Ethernet frame")
    ether_type = struct.unpack_from("!H", frame, 12)[0]
    return _parse_ethertype_payload(ether_type, frame[14:])


def _parse_ethertype_payload(protocol: int, packet: bytes) -> PacketFacts:
    vlan_depth = 0
    while protocol in (0x8100, 0x88A8):
        if vlan_depth >= 2 or len(packet) < 4:
            raise ValueError("truncated or excessive VLAN tag stack")
        protocol = struct.unpack_from("!H", packet, 2)[0]
        packet = packet[4:]
        vlan_depth += 1
    if protocol == 0x0800:
        return _parse_ipv4_payload(packet)
    if protocol == 0x86DD:
        return _parse_ipv6_payload(packet)
    raise ValueError("capture contains a non-IPv4/IPv6 network packet")


def _parse_raw_ip_payload(packet: bytes) -> PacketFacts:
    if not packet:
        raise ValueError("truncated raw IP packet")
    version = packet[0] >> 4
    if version == 4:
        return _parse_ipv4_payload(packet)
    if version == 6:
        return _parse_ipv6_payload(packet)
    raise ValueError("raw capture packet has an unsupported IP version")


def _parse_linux_sll_payload(packet: bytes) -> PacketFacts:
    if len(packet) < 16:
        raise ValueError("truncated Linux cooked SLL header")
    address_length = struct.unpack_from("!H", packet, 4)[0]
    if address_length > 8:
        raise ValueError("invalid Linux cooked SLL address length")
    protocol = struct.unpack_from("!H", packet, 14)[0]
    return _parse_ethertype_payload(protocol, packet[16:])


def _parse_linux_sll2_payload(packet: bytes) -> PacketFacts:
    if len(packet) < 20:
        raise ValueError("truncated Linux cooked SLL2 header")
    protocol, reserved = struct.unpack_from("!HH", packet, 0)
    address_length = packet[11]
    if reserved != 0 or address_length > 8:
        raise ValueError("invalid Linux cooked SLL2 header")
    return _parse_ethertype_payload(protocol, packet[20:])


def _parse_ipv4_payload(packet: bytes) -> PacketFacts:
    if len(packet) < 20 or packet[0] >> 4 != 4:
        raise ValueError("truncated or malformed IPv4 packet")
    header_length = (packet[0] & 0x0F) * 4
    total_length = struct.unpack_from("!H", packet, 2)[0]
    fragment = struct.unpack_from("!H", packet, 6)[0]
    if header_length < 20 or total_length < header_length or total_length > len(packet):
        raise ValueError("invalid IPv4 length")
    if fragment & 0x3FFF:
        raise ValueError("fragmented IPv4 packets are not a supported oracle seam")
    return _parse_transport_payload(
        ip_version=4,
        protocol=packet[9],
        source_address=packet[12:16],
        destination_address=packet[16:20],
        segment=packet[header_length:total_length],
    )


def _parse_ipv6_payload(packet: bytes) -> PacketFacts:
    if len(packet) < 40 or packet[0] >> 4 != 6:
        raise ValueError("truncated or malformed IPv6 packet")
    payload_length = struct.unpack_from("!H", packet, 4)[0]
    if 40 + payload_length > len(packet):
        raise ValueError("invalid IPv6 length")
    next_header = packet[6]
    offset = 40
    end = 40 + payload_length
    extensions = 0
    while next_header in (0, 43, 60, 51):
        if offset + 2 > end or extensions >= 8:
            raise ValueError("malformed IPv6 extension chain")
        extension_length = (
            (packet[offset + 1] + 2) * 4
            if next_header == 51
            else (packet[offset + 1] + 1) * 8
        )
        if extension_length < 8 or offset + extension_length > end:
            raise ValueError("malformed IPv6 extension length")
        next_header = packet[offset]
        offset += extension_length
        extensions += 1
    if next_header == 44:
        raise ValueError("fragmented IPv6 packets are not a supported oracle seam")
    return _parse_transport_payload(
        ip_version=6,
        protocol=next_header,
        source_address=packet[8:24],
        destination_address=packet[24:40],
        segment=packet[offset:end],
    )


def _parse_transport_payload(
    *,
    ip_version: int,
    protocol: int,
    source_address: bytes,
    destination_address: bytes,
    segment: bytes,
) -> PacketFacts:
    if protocol == 6:
        if len(segment) < 20:
            raise ValueError("truncated TCP segment")
        header_length = (segment[12] >> 4) * 4
        if header_length < 20 or header_length > len(segment):
            raise ValueError("invalid TCP header length")
        source_port, destination_port, sequence = struct.unpack_from("!HHI", segment)
        return PacketFacts(
            payload=segment[header_length:],
            ip_version=ip_version,
            transport="tcp",
            source_address=source_address,
            destination_address=destination_address,
            source_port=source_port,
            destination_port=destination_port,
            tcp_sequence=sequence,
        )
    if protocol == 17:
        if len(segment) < 8:
            raise ValueError("truncated UDP datagram")
        udp_length = struct.unpack_from("!H", segment, 4)[0]
        if udp_length < 8 or udp_length > len(segment):
            raise ValueError("invalid UDP length")
        source_port, destination_port = struct.unpack_from("!HH", segment)
        return PacketFacts(
            payload=segment[8:udp_length],
            ip_version=ip_version,
            transport="udp",
            source_address=source_address,
            destination_address=destination_address,
            source_port=source_port,
            destination_port=destination_port,
            tcp_sequence=None,
        )
    raise ValueError("capture contains a non-TCP/UDP IP packet")


def iter_classic_pcap(path: Path, expected_digest: str) -> Iterator[Packet]:
    if path.is_symlink() or not path.is_file():
        raise ValueError(f"{path.name} must be a regular non-symlink file")
    if path.stat().st_size > MAX_CAPTURE_BYTES:
        raise ValueError(f"{path.name} exceeds the capture size bound")
    data = path.read_bytes()
    if len(data) > MAX_CAPTURE_BYTES:
        raise ValueError(f"{path.name} exceeds the capture size bound")
    actual_digest = hashlib.sha256(data).hexdigest()
    if actual_digest != expected_digest:
        raise ValueError(f"{path.name} digest mismatch")
    if len(data) < 24:
        raise ValueError("truncated classic PCAP header")
    magic = data[:4]
    formats = {
        b"\xd4\xc3\xb2\xa1": ("<", 1_000),
        b"\xa1\xb2\xc3\xd4": (">", 1_000),
        b"\x4d\x3c\xb2\xa1": ("<", 1),
        b"\xa1\xb2\x3c\x4d": (">", 1),
    }
    if magic not in formats:
        raise ValueError("input is not a supported classic PCAP")
    endian, fraction_to_ns = formats[magic]
    major, minor, _zone, _sigfigs, snaplen, linktype = struct.unpack_from(
        endian + "HHIIII", data, 4
    )
    if (major, minor) != (2, 4):
        raise ValueError("PCAP must use classic version 2.4")
    link_parsers = {
        1: _parse_ethernet_payload,
        101: _parse_raw_ip_payload,
        113: _parse_linux_sll_payload,
        276: _parse_linux_sll2_payload,
    }
    packet_parser = link_parsers.get(linktype)
    if packet_parser is None:
        raise ValueError(f"unsupported PCAP linktype: {linktype}")
    if snaplen < 1 or snaplen > MAX_PACKET_BYTES:
        raise ValueError("PCAP snaplen is outside the supported bound")
    offset = 24
    packet_count = 0
    while offset < len(data):
        if len(data) - offset < 16:
            raise ValueError("truncated PCAP packet header")
        seconds, fraction, captured_length, original_length = struct.unpack_from(
            endian + "IIII", data, offset
        )
        offset += 16
        fraction_limit = 1_000_000_000 if fraction_to_ns == 1 else 1_000_000
        if fraction >= fraction_limit:
            raise ValueError("invalid PCAP packet timestamp fraction")
        if captured_length > original_length:
            raise ValueError("captured packet length exceeds original length")
        if captured_length > snaplen or captured_length > MAX_PACKET_BYTES:
            raise ValueError("captured packet exceeds the snaplen or size bound")
        if original_length > MAX_ORIGINAL_PACKET_BYTES:
            raise ValueError("original packet length exceeds the size bound")
        if captured_length > len(data) - offset:
            raise ValueError("truncated PCAP packet data")
        frame = data[offset : offset + captured_length]
        offset += captured_length
        packet_count += 1
        if packet_count > MAX_PACKETS:
            raise ValueError("PCAP packet count exceeds the supported bound")
        timestamp_ns = seconds * 1_000_000_000 + fraction * fraction_to_ns
        if captured_length < original_length:
            yield Packet(timestamp_ns, None)
        else:
            facts = packet_parser(frame)
            yield Packet(
                timestamp_ns=timestamp_ns,
                payload=facts.payload,
                ip_version=facts.ip_version,
                transport=facts.transport,
                source_address=facts.source_address,
                destination_address=facts.destination_address,
                source_port=facts.source_port,
                destination_port=facts.destination_port,
                tcp_sequence=facts.tcp_sequence,
            )


def _known_marker_matches(
    payload: bytes, marker_owners: dict[bytes, tuple[str, str]]
) -> list[tuple[str, str]]:
    matches: list[tuple[str, str]] = []
    for match in WIRE_MARKER_RE.finditer(payload):
        owner = marker_owners.get(match.group(1))
        if owner is not None:
            matches.append(owner)
    return matches


def _locate_marker_intervals(
    *,
    role: str,
    packets: list[Packet],
    windows: list[Window],
    semantic_rules: dict[str, SemanticRule],
    marker_owners: dict[bytes, tuple[str, str]],
) -> list[tuple[int, int]]:
    positions: dict[tuple[str, str], MarkerPosition] = {}
    has_startup_rule = any(
        rule.name == STARTUP_RULE for rule in semantic_rules.values()
    )
    startup_streams: dict[
        tuple[bytes, int, bytes, int], list[tuple[int, int, int, bytes]]
    ] = {}
    for record_index, packet in enumerate(packets):
        if packet.payload is None:
            continue
        if has_startup_rule and packet.transport == "tcp" and packet.payload:
            assert packet.source_address is not None
            assert packet.destination_address is not None
            assert packet.source_port is not None
            assert packet.destination_port is not None
            assert packet.tcp_sequence is not None
            flow = (
                packet.source_address,
                packet.source_port,
                packet.destination_address,
                packet.destination_port,
            )
            startup_streams.setdefault(flow, []).append(
                (packet.tcp_sequence, record_index, packet.timestamp_ns, packet.payload)
            )
        matches = _known_marker_matches(packet.payload, marker_owners)
        if len(matches) > 1:
            raise ValueError(f"{role} packet contains duplicate or multiple markers")
        if matches:
            owner = matches[0]
            if semantic_rules[owner[0]].name == STARTUP_RULE:
                continue
            if owner in positions:
                raise ValueError(
                    f"{role} capture contains a duplicate {owner[1]} marker"
                )
            positions[owner] = MarkerPosition(
                record_index, record_index, packet.timestamp_ns, packet.timestamp_ns
            )

    for segments in startup_streams.values():
        unique: dict[tuple[int, bytes], tuple[int, int]] = {}
        for sequence, record_index, timestamp_ns, payload in segments:
            unique.setdefault((sequence, payload), (record_index, timestamp_ns))
        ordered = sorted(
            (
                sequence,
                record_index,
                timestamp_ns,
                payload,
            )
            for (sequence, payload), (record_index, timestamp_ns) in unique.items()
        )
        chunks: list[tuple[bytearray, list[int], list[int]]] = []
        chunk_start = -1
        chunk_end = -1
        for sequence, record_index, timestamp_ns, payload in ordered:
            if not payload:
                continue
            if not chunks or sequence > chunk_end:
                chunks.append(
                    (
                        bytearray(payload),
                        [record_index] * len(payload),
                        [timestamp_ns] * len(payload),
                    )
                )
                chunk_start = sequence
                chunk_end = sequence + len(payload)
                continue
            if sequence < chunk_start:
                raise ValueError(
                    f"{role} TCP marker stream has unsupported sequence wrap"
                )
            offset = sequence - chunk_start
            chunk, record_indexes, timestamps = chunks[-1]
            overlap = min(len(payload), max(0, len(chunk) - offset))
            if bytes(chunk[offset : offset + overlap]) != payload[:overlap]:
                raise ValueError(
                    f"{role} TCP marker retransmission has conflicting bytes"
                )
            suffix = payload[overlap:]
            if suffix:
                chunk.extend(suffix)
                record_indexes.extend([record_index] * len(suffix))
                timestamps.extend([timestamp_ns] * len(suffix))
                chunk_end = chunk_start + len(chunk)
        for chunk, record_indexes, timestamps in chunks:
            for match in WIRE_MARKER_RE.finditer(chunk):
                owner = marker_owners.get(match.group(1))
                if owner is None or semantic_rules[owner[0]].name != STARTUP_RULE:
                    continue
                if owner in positions:
                    raise ValueError(
                        f"{role} capture contains a duplicate {owner[1]} marker flow"
                    )
                marker_indexes = record_indexes[match.start() : match.end()]
                marker_timestamps = timestamps[match.start() : match.end()]
                positions[owner] = MarkerPosition(
                    min(marker_indexes),
                    max(marker_indexes),
                    min(marker_timestamps),
                    max(marker_timestamps),
                )

    intervals: list[tuple[int, int]] = []
    previous_outcome: MarkerPosition | None = None
    for window in windows:
        action = positions.get((window.identifier, "action"))
        outcome = positions.get((window.identifier, "outcome"))
        if action is None or outcome is None:
            raise ValueError(
                f"{role} window {window.identifier} must contain exactly one action "
                "and one outcome marker"
            )
        if (
            action.last_record_index >= outcome.first_record_index
            or action.last_timestamp_ns > outcome.first_timestamp_ns
        ):
            raise ValueError(
                f"{role} window {window.identifier} action marker must precede outcome marker"
            )
        if previous_outcome is not None and (
            previous_outcome.last_record_index >= action.first_record_index
            or previous_outcome.last_timestamp_ns > action.first_timestamp_ns
        ):
            raise ValueError(
                f"{role} marker intervals must follow plan order without overlap"
            )
        intervals.append((action.first_record_index, outcome.last_record_index))
        previous_outcome = outcome
    return intervals


def _is_fixture_packet(packet: Packet, fixture: FixtureContext, port: int) -> bool:
    return (
        packet.source_address == fixture.address and packet.source_port == port
    ) or (
        packet.destination_address == fixture.address
        and packet.destination_port == port
    )


def _read_dns_name(
    payload: bytes, offset: int, *, depth: int = 0
) -> tuple[tuple[bytes, ...], int]:
    if depth > 8:
        raise ValueError("fixture DNS name compression is recursive")
    labels: list[bytes] = []
    next_offset: int | None = None
    while True:
        if offset >= len(payload):
            raise ValueError("fixture DNS name is truncated")
        length = payload[offset]
        if length & 0xC0 == 0xC0:
            if offset + 2 > len(payload):
                raise ValueError("fixture DNS compression pointer is truncated")
            pointer = ((length & 0x3F) << 8) | payload[offset + 1]
            if pointer >= len(payload):
                raise ValueError("fixture DNS compression pointer is out of bounds")
            pointed_labels, _ignored = _read_dns_name(payload, pointer, depth=depth + 1)
            labels.extend(pointed_labels)
            next_offset = offset + 2
            break
        offset += 1
        if length == 0:
            next_offset = offset
            break
        if length > 63 or offset + length > len(payload):
            raise ValueError("fixture DNS name is malformed")
        labels.append(payload[offset : offset + length].lower())
        offset += length
    assert next_offset is not None
    return tuple(labels), next_offset


def _dns_packet_facts(payload: bytes) -> DnsPacketFacts:
    if len(payload) < 12:
        raise ValueError("fixture DNS packet is shorter than its header")
    (
        transaction_id,
        flags,
        question_count,
        answer_count,
        authority_count,
        additional_count,
    ) = struct.unpack_from("!HHHHHH", payload)
    if question_count != 1:
        raise ValueError("fixture DNS packet must contain exactly one question")
    if authority_count or additional_count:
        raise ValueError("fixture DNS authority/additional sections are unsupported")
    labels, offset = _read_dns_name(payload, 12)
    if offset + 4 > len(payload):
        raise ValueError("fixture DNS question type/class is truncated")
    query_type, query_class = struct.unpack_from("!HH", payload, offset)
    offset += 4
    answers: list[tuple[tuple[bytes, ...], bytes]] = []
    for record_index in range(answer_count + authority_count + additional_count):
        answer_name, offset = _read_dns_name(payload, offset)
        if offset + 10 > len(payload):
            raise ValueError("fixture DNS answer header is truncated")
        answer_type, answer_class, _ttl, data_length = struct.unpack_from(
            "!HHIH", payload, offset
        )
        offset += 10
        if offset + data_length > len(payload):
            raise ValueError("fixture DNS answer data is truncated")
        answer_data = payload[offset : offset + data_length]
        offset += data_length
        if (
            record_index < answer_count
            and answer_type == 1
            and answer_class == 1
            and data_length == 4
        ):
            answers.append((answer_name, answer_data))
    if offset != len(payload):
        raise ValueError("fixture DNS packet contains trailing unparsed bytes")
    is_response = bool(flags & 0x8000)
    if not is_response and (answer_count or authority_count or additional_count):
        raise ValueError("fixture DNS query contains resource records")
    identity = (
        struct.pack("!H", transaction_id)
        + b".".join(labels)
        + struct.pack("!HH", query_type, query_class)
    )
    return DnsPacketFacts(
        identity=identity,
        question_labels=labels,
        is_response=is_response,
        rcode=flags & 0x000F,
        answer_records=tuple(answers),
    )


def _require_receipt_bound_dns(
    *,
    role: str,
    packets: list[Packet],
    fixture: FixtureContext,
    expected_query_sha256: str,
    expected_exchange_count: int,
) -> tuple[bytes, set[int]]:
    queries: list[tuple[int, DnsPacketFacts, Packet]] = []
    responses: list[tuple[int, DnsPacketFacts, Packet]] = []
    for record_index, packet in enumerate(packets):
        if packet.payload is None or packet.transport != "udp":
            continue
        if not _is_fixture_packet(packet, fixture, fixture.dns_udp_port):
            continue
        facts = _dns_packet_facts(packet.payload)
        query_name = b".".join(facts.question_labels)
        query_sha256 = hashlib.sha256(
            b"ripdpi:startup-dns-query:v1:" + query_name
        ).hexdigest()
        if query_sha256 != expected_query_sha256:
            continue
        if (
            len(facts.question_labels) != len(fixture.fixture_domain_labels) + 1
            or re.fullmatch(rb"startup-[0-9]+", facts.question_labels[0]) is None
            or facts.question_labels[1:] != fixture.fixture_domain_labels
            or not facts.identity.endswith(struct.pack("!HH", 1, 1))
        ):
            raise ValueError(
                f"{role} post-ready fixture DNS question is not source-owned"
            )
        if facts.is_response:
            if (
                packet.source_address == fixture.address
                and packet.source_port == fixture.dns_udp_port
            ):
                responses.append((record_index, facts, packet))
        else:
            if (
                packet.destination_address == fixture.address
                and packet.destination_port == fixture.dns_udp_port
            ):
                queries.append((record_index, facts, packet))
    valid_pairs = [
        (query, response)
        for query in queries
        for response in responses
        if query[0] < response[0]
        and query[1].identity == response[1].identity
        and query[1].rcode == 0
        and response[1].rcode == 0
        and response[1].answer_records
        == ((query[1].question_labels, fixture.dns_answer_address),)
        and query[2].source_address == response[2].destination_address
        and query[2].source_port == response[2].destination_port
        and query[2].destination_address == response[2].source_address
        and query[2].destination_port == response[2].source_port
    ]
    if len(valid_pairs) != expected_exchange_count:
        raise ValueError(
            f"{role} must contain exactly {expected_exchange_count} matching NOERROR "
            "post-ready fixture DNS query/response pair(s)"
        )
    identities = [query[1].identity for query, _response in valid_pairs]
    if len(identities) != len(set(identities)):
        raise ValueError(f"{role} post-ready fixture DNS exchange identities repeat")
    proof = hashlib.sha256(
        b"ripdpi:startup-dns-proof:v2:"
        + bytes((expected_exchange_count,))
        + b"".join(sorted(identities))
        + fixture.dns_answer_address
    ).digest()
    return proof, {
        record_index
        for query, response in valid_pairs
        for record_index in (query[0], response[0])
    }


def _dns_evidence_query_sha256(labels: tuple[bytes, ...]) -> str:
    return hashlib.sha256(
        b"ripdpi:dns-evidence-query:v1:" + b".".join(labels).lower()
    ).hexdigest()


def _parse_fixture_endpoint(value: str, context: str) -> tuple[bytes, int]:
    if value.startswith("["):
        closing = value.find("]")
        if closing <= 1 or closing + 1 >= len(value) or value[closing + 1] != ":":
            raise ValueError(f"{context} is malformed")
        host, port_text = value[1:closing], value[closing + 2 :]
    else:
        host, separator, port_text = value.rpartition(":")
        if not separator or not host or ":" in host:
            raise ValueError(f"{context} is malformed")
    try:
        address = ipaddress.ip_address(host)
        port = int(port_text)
    except ValueError as error:
        raise ValueError(f"{context} is malformed") from error
    if (
        port not in range(1, 65536)
        or address.is_unspecified
        or address.is_multicast
    ):
        raise ValueError(f"{context} is malformed")
    return address.packed, port


def _fixture_event_field_sha256(field: str, value: str) -> str:
    return hashlib.sha256(
        f"ripdpi:network-evidence-fixture-event-field:v1:{field}:".encode("ascii")
        + value.encode("utf-8")
    ).hexdigest()


def _redacted_fixture_event(event: dict[str, Any]) -> dict[str, Any]:
    return {
        "service": event["service"],
        "protocol": event["protocol"],
        "peerSha256": _fixture_event_field_sha256("peer", event["peer"]),
        "targetSha256": _fixture_event_field_sha256("target", event["target"]),
        "detailSha256": _fixture_event_field_sha256("detail", event["detail"]),
        "bytes": event["bytes"],
        "sniSha256": (
            None
            if event["sni"] is None
            else _fixture_event_field_sha256("sni", event["sni"])
        ),
        "createdAt": event["createdAt"],
    }


def _dns_query_wire_size(query_host: str) -> int:
    labels = query_host.encode("ascii").split(b".")
    if any(not label or len(label) > 63 for label in labels):
        raise ValueError("DNS query host labels are malformed")
    return 12 + sum(1 + len(label) for label in labels) + 1 + 4


def _load_fixture_transcript(
    path: Path,
    *,
    gate_id: str,
    plan: dict[str, Any],
    fixture: FixtureContext,
    facts: dict[str, Any],
) -> EncryptedDnsTranscriptProof:
    value, raw = load_strict_json(
        path, "fixture transcript", maximum_bytes=MAX_TRANSCRIPT_BYTES
    )
    if hashlib.sha256(raw).hexdigest() != facts["fixtureTranscriptSha256"]:
        raise ValueError(f"{gate_id} fixture transcript digest mismatch")
    required = {
        "version",
        "gateId",
        "correlationId",
        "fixtureIdentitySha256",
        "queryHost",
        "querySha256",
        "events",
        "eventInventory",
    }
    if (
        set(value) != required
        or value["version"] != "network_evidence_fixture_transcript_v2"
    ):
        raise ValueError(f"{gate_id} fixture transcript fields or version mismatch")
    if (
        value["gateId"] != gate_id
        or value["correlationId"] != plan["correlationId"]
        or value["fixtureIdentitySha256"] != fixture.identity_sha256
    ):
        raise ValueError(f"{gate_id} fixture transcript identity mismatch")
    query_field = {
        "dns-virtual-vpn-resolver": "virtualQuerySha256",
        "dns-proxied-through-tunnelled-resolver": "proxiedQuerySha256",
        "dns-no-isp-fallback-on-encrypted-resolver-outage": "outageQuerySha256",
    }[gate_id]
    query_host = value["queryHost"]
    if not isinstance(query_host, str):
        raise ValueError(f"{gate_id} fixture transcript queryHost is malformed")
    try:
        normalized_query_host = query_host.lower().encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError(
            f"{gate_id} fixture transcript queryHost is malformed"
        ) from error
    expected_query = hashlib.sha256(
        b"ripdpi:dns-evidence-query:v1:" + normalized_query_host
    ).hexdigest()
    if value["querySha256"] != expected_query or expected_query != facts[query_field]:
        raise ValueError(f"{gate_id} fixture transcript query binding mismatch")
    expected_query_host = (
        f"{gate_id.removeprefix('dns-')[:24]}-{plan['correlationId'][:16]}."
        + b".".join(fixture.fixture_domain_labels).decode("ascii")
    )
    if query_host != expected_query_host:
        raise ValueError(f"{gate_id} fixture transcript queryHost is not the expected target")
    provider_field = {
        "dns-virtual-vpn-resolver": "tunnelResolverProviderSha256",
        "dns-proxied-through-tunnelled-resolver": "resolverProviderSha256",
        "dns-no-isp-fallback-on-encrypted-resolver-outage": "encryptedResolverProviderSha256",
    }[gate_id]
    expected_provider = hashlib.sha256(
        (
            "ripdpi:dns-evidence-resolver:v1:dot:"
            f"{ipaddress.ip_address(fixture.address)}:{fixture.dns_dot_port}"
        ).encode("ascii")
    ).hexdigest()
    if facts[provider_field] != expected_provider:
        raise ValueError(f"{gate_id} resolver provider digest mismatch")
    if gate_id == "dns-virtual-vpn-resolver":
        expected_virtual_address = hashlib.sha256(
            b"ripdpi:dns-evidence-virtual-dns:v1:198.18.0.53"
        ).hexdigest()
        if facts["virtualDnsAddressSha256"] != expected_virtual_address:
            raise ValueError(f"{gate_id} virtual resolver address digest mismatch")
    if gate_id == "dns-no-isp-fallback-on-encrypted-resolver-outage":
        expected_fault_control = hashlib.sha256(
            b"ripdpi:dns-evidence-fault:v1:dns_dot:dns_timeout:persistent"
        ).hexdigest()
        if facts["faultControlSha256"] != expected_fault_control:
            raise ValueError(f"{gate_id} fault control digest mismatch")
    events = value["events"]
    if not isinstance(events, list) or not events:
        raise ValueError(f"{gate_id} fixture transcript events are missing")
    allowed_fields = {
        "service",
        "protocol",
        "peer",
        "target",
        "detail",
        "bytes",
        "sni",
        "createdAt",
    }
    for event in events:
        if not isinstance(event, dict) or set(event) != allowed_fields:
            raise ValueError(f"{gate_id} fixture transcript event fields mismatch")
        if any(
            not isinstance(event[field], str)
            for field in ("service", "protocol", "peer", "target", "detail")
        ) or not isinstance(event["createdAt"], int) or isinstance(
            event["createdAt"], bool
        ):
            raise ValueError(f"{gate_id} fixture transcript event value mismatch")
        if (
            not isinstance(event["bytes"], int)
            or isinstance(event["bytes"], bool)
            or event["bytes"] < 0
            or (event["sni"] is not None and not isinstance(event["sni"], str))
        ):
            raise ValueError(f"{gate_id} fixture transcript event value mismatch")
    inventory = value["eventInventory"]
    inventory_fields = {
        "service",
        "protocol",
        "peerSha256",
        "targetSha256",
        "detailSha256",
        "bytes",
        "sniSha256",
        "createdAt",
    }
    if not isinstance(inventory, list) or not inventory:
        raise ValueError(f"{gate_id} fixture transcript event inventory is missing")
    for item in inventory:
        if not isinstance(item, dict) or set(item) != inventory_fields:
            raise ValueError(f"{gate_id} fixture transcript event inventory mismatch")
        if (
            not isinstance(item["service"], str)
            or not isinstance(item["protocol"], str)
            or any(
                not isinstance(item[field], str)
                or not re.fullmatch(SHA256_RE, item[field])
                for field in ("peerSha256", "targetSha256", "detailSha256")
            )
            or (
                item["sniSha256"] is not None
                and (
                    not isinstance(item["sniSha256"], str)
                    or not re.fullmatch(SHA256_RE, item["sniSha256"])
                )
            )
            or not isinstance(item["bytes"], int)
            or isinstance(item["bytes"], bool)
            or item["bytes"] < 0
            or not isinstance(item["createdAt"], int)
            or isinstance(item["createdAt"], bool)
        ):
            raise ValueError(f"{gate_id} fixture transcript event inventory mismatch")
    forbidden_resolver_events = {"dns_udp", "dns_http", "dns_dnscrypt", "dns_doq"}
    if any(item["service"] in forbidden_resolver_events for item in inventory):
        raise ValueError(
            f"{gate_id} transcript contains plaintext or alternate resolver use"
        )
    expected_inventory = [_redacted_fixture_event(event) for event in events]
    detailed_index = 0
    auxiliary_inventory: list[dict[str, Any]] = []
    for item in inventory:
        if (
            detailed_index < len(expected_inventory)
            and item == expected_inventory[detailed_index]
        ):
            detailed_index += 1
        else:
            auxiliary_inventory.append(item)
    if detailed_index != len(expected_inventory):
        raise ValueError(
            f"{gate_id} detailed events are not an ordered subset of the redacted inventory"
        )
    expected_dot_target = (fixture.address, fixture.dns_dot_port)
    expected_sni = b".".join(fixture.fixture_domain_labels).decode("ascii")
    query_events = [event for event in events if event["detail"] == query_host]
    if len(query_events) != 1:
        raise ValueError(f"{gate_id} must bind exactly one DoT query transcript")
    query_event = query_events[0]
    query_wire_bytes = _dns_query_wire_size(query_host)
    query_peer = _parse_fixture_endpoint(
        query_event["peer"], f"{gate_id} fixture transcript query peer"
    )
    query_target = _parse_fixture_endpoint(
        query_event["target"], f"{gate_id} fixture transcript query target"
    )
    if (
        query_event["service"] != "dns_dot"
        or query_event["protocol"] != "dot"
        or query_target != expected_dot_target
        or query_event["sni"] != expected_sni
        or query_event["bytes"] != query_wire_bytes
    ):
        raise ValueError(
            f"{gate_id} DoT query transcript target, SNI, or byte count mismatch"
        )
    window = next(
        (item for item in plan["windows"] if item["id"] == gate_id), None
    )
    if window is None:
        raise ValueError(f"{gate_id} fixture transcript has no scenario window")
    started_ms = window["startedAtEpoch"] * 1_000
    finished_ms = (window["finishedAtEpoch"] + 1) * 1_000 - 1
    if not started_ms <= query_event["createdAt"] <= finished_ms:
        raise ValueError(f"{gate_id} DoT query transcript timestamp is outside its window")
    query_inventory = _redacted_fixture_event(query_event)
    accept_detail_sha256 = _fixture_event_field_sha256("detail", "accept")
    if len(auxiliary_inventory) != 1:
        raise ValueError(f"{gate_id} must contain one redacted DoT accept event")
    accept = auxiliary_inventory[0]
    if (
        accept["service"] != "dns_dot"
        or accept["protocol"] != "dot"
        or accept["peerSha256"] != query_inventory["peerSha256"]
        or accept["targetSha256"] != query_inventory["targetSha256"]
        or accept["detailSha256"] != accept_detail_sha256
        or accept["bytes"] != 0
        or accept["sniSha256"] is not None
        or not started_ms <= accept["createdAt"] <= query_event["createdAt"]
    ):
        raise ValueError(f"{gate_id} redacted DoT accept event binding mismatch")
    fault_events = [
        event
        for event in events
        if event["service"] == "dns_dot" and str(event["detail"]).startswith("fault:")
    ]
    if gate_id == "dns-no-isp-fallback-on-encrypted-resolver-outage":
        if len(fault_events) != 1 or fault_events[0]["detail"] != "fault:DnsTimeout":
            raise ValueError(
                f"{gate_id} deterministic DoT timeout transcript is missing"
            )
        fault_event = fault_events[0]
        if (
            _parse_fixture_endpoint(
                fault_event["peer"], f"{gate_id} fixture transcript fault peer"
            )
            != query_peer
            or _parse_fixture_endpoint(
                fault_event["target"], f"{gate_id} fixture transcript fault target"
            )
            != query_target
            or fault_event["protocol"] != "dot"
            or fault_event["sni"] != expected_sni
            or fault_event["bytes"] != query_wire_bytes
            or fault_event["createdAt"] < query_event["createdAt"]
            or fault_event["createdAt"] > finished_ms
        ):
            raise ValueError(f"{gate_id} deterministic DoT fault binding mismatch")
    elif fault_events:
        raise ValueError(f"{gate_id} transcript contains an unexpected resolver fault")
    socks_events = [
        event
        for event in events
        if event["service"] == "socks5_relay" and event["protocol"] == "tcp"
    ]
    if gate_id == "dns-proxied-through-tunnelled-resolver":
        expected_target_value = (
            f"{ipaddress.ip_address(fixture.address)}:{fixture.dns_dot_port}"
        )
        if len(socks_events) != 1 or socks_events[0]["detail"] != expected_target_value:
            raise ValueError(
                f"{gate_id} exact fixture SOCKS target transcript is missing"
            )
        expected_target = hashlib.sha256(
            ("ripdpi:dns-evidence-socks-target:v1:" + expected_target_value).encode(
                "utf-8"
            )
        ).hexdigest()
        if expected_target != facts["socksTargetSha256"]:
            raise ValueError(f"{gate_id} fixture SOCKS target digest mismatch")
        if (
            facts["relayDnsRoute"] != "socks5"
            or facts["relayDnsFailClosed"] is not True
        ):
            raise ValueError(
                f"{gate_id} receipt does not prove fail-closed SOCKS DNS routing"
            )
        socks_event = socks_events[0]
        socks_peer = _parse_fixture_endpoint(
            socks_event["peer"], f"{gate_id} fixture transcript SOCKS peer"
        )
        socks_target = _parse_fixture_endpoint(
            socks_event["target"], f"{gate_id} fixture transcript SOCKS target"
        )
        if (
            socks_target != (fixture.address, fixture.socks5_port)
            or socks_event["sni"] is not None
            or socks_event["createdAt"] > query_event["createdAt"]
            or socks_event["createdAt"] < started_ms
        ):
            raise ValueError(f"{gate_id} fixture SOCKS event binding mismatch")
        flow_peer = socks_peer
        flow_target = socks_target
    elif socks_events:
        raise ValueError(
            f"{gate_id} transcript unexpectedly contains fixture SOCKS routing"
        )
    else:
        flow_peer = query_peer
        flow_target = query_target
    if gate_id == "dns-proxied-through-tunnelled-resolver":
        expected_event_sequence = [socks_events[0], query_event]
    elif gate_id == "dns-no-isp-fallback-on-encrypted-resolver-outage":
        expected_event_sequence = [query_event, fault_events[0]]
    else:
        expected_event_sequence = [query_event]
    if events != expected_event_sequence or any(
        previous["createdAt"] > current["createdAt"]
        for previous, current in zip(events, events[1:])
    ):
        raise ValueError(f"{gate_id} fixture transcript event order mismatch")
    return EncryptedDnsTranscriptProof(
        digest=hashlib.sha256(
            b"ripdpi:dns-evidence-fixture-transcript-proof:v1:" + raw
        ).digest(),
        flow_peer=flow_peer,
        flow_target=flow_target,
        query_created_at_ms=query_event["createdAt"],
        query_wire_bytes=query_wire_bytes,
        tls_target=query_target,
    )


def _require_encrypted_dns_role_packets(
    *,
    role: str,
    packets: list[Packet],
    interval: tuple[int, int],
    gate_id: str,
    fixture: FixtureContext,
    transcript_proof: EncryptedDnsTranscriptProof,
) -> tuple[EncryptedDnsRoleProof, set[int]]:
    start, finish = interval
    del fixture
    candidates: dict[tuple[bytes, int], dict[str, list[int]]] = {}
    for index in range(start, finish + 1):
        packet = packets[index]
        if packet.transport in {"tcp", "udp"} and (
            packet.source_port == 53 or packet.destination_port == 53
        ):
            raise ValueError(f"{role} {gate_id} contains plaintext DNS")
        if packet.transport != "tcp":
            continue
        if (
            packet.destination_address == transcript_proof.flow_target[0]
            and packet.destination_port == transcript_proof.flow_target[1]
        ):
            assert packet.source_address is not None and packet.source_port is not None
            source = (packet.source_address, packet.source_port)
            candidates.setdefault(source, {"outbound": [], "inbound": []})[
                "outbound"
            ].append(index)
        elif (
            packet.source_address == transcript_proof.flow_target[0]
            and packet.source_port == transcript_proof.flow_target[1]
        ):
            assert (
                packet.destination_address is not None
                and packet.destination_port is not None
            )
            source = (packet.destination_address, packet.destination_port)
            candidates.setdefault(source, {"outbound": [], "inbound": []})[
                "inbound"
            ].append(index)
    complete = [
        (source, directions)
        for source, directions in candidates.items()
        if directions["outbound"] and directions["inbound"]
    ]
    if role == "external-observer":
        complete = [
            item for item in complete if item[0] == transcript_proof.flow_peer
        ]
    if len(complete) != 1:
        raise ValueError(
            f"{role} {gate_id} must contain one transcript-bound bidirectional TCP flow"
        )
    source, directions = complete[0]
    outbound_stream = _reassemble_tcp_direction(
        role=role,
        gate_id=gate_id,
        packets=packets,
        record_indexes=directions["outbound"],
        direction="outbound",
    )
    inbound_stream = _reassemble_tcp_direction(
        role=role,
        gate_id=gate_id,
        packets=packets,
        record_indexes=directions["inbound"],
        direction="inbound",
    )
    through_socks = gate_id == "dns-proxied-through-tunnelled-resolver"
    outbound_tls = _tls_record_digest(
        outbound_stream,
        role,
        gate_id,
        "outbound",
        socks_direction="outbound" if through_socks else None,
        socks_target=transcript_proof.tls_target,
    )
    inbound_tls = _tls_record_digest(
        inbound_stream,
        role,
        gate_id,
        "inbound",
        socks_direction="inbound" if through_socks else None,
        socks_target=None,
    )
    if not any(
        length >= transcript_proof.query_wire_bytes + 2
        for length in outbound_tls.application_data_lengths
    ):
        raise ValueError(
            f"{role} {gate_id} TLS flow does not carry the transcript-bound DNS query bytes"
        )
    allowed = set(directions["outbound"] + directions["inbound"])
    timestamps = tuple(sorted(packets[index].timestamp_ns for index in allowed))
    event_ns = transcript_proof.query_created_at_ms * 1_000_000
    if all(abs(timestamp - event_ns) > 2_000_000_000 for timestamp in timestamps):
        raise ValueError(
            f"{role} {gate_id} TLS flow is not time-bound to the fixture query event"
        )
    proof = EncryptedDnsRoleProof(
        transcript_digest=transcript_proof.digest,
        source=source,
        target=transcript_proof.flow_target,
        outbound_tls_digest=outbound_tls.digest,
        inbound_tls_digest=inbound_tls.digest,
        packet_timestamps_ns=timestamps,
        normalized_flow_digest=_normalized_flow_digest(
            packets, directions["outbound"], directions["inbound"]
        ),
    )
    return proof, allowed


def _reassemble_tcp_direction(
    *,
    role: str,
    gate_id: str,
    packets: list[Packet],
    record_indexes: list[int],
    direction: str,
) -> bytes:
    segments: dict[int, bytes] = {}
    anchor_raw: int | None = None
    for index in record_indexes:
        packet = packets[index]
        if not packet.payload:
            continue
        assert packet.tcp_sequence is not None
        if anchor_raw is None:
            anchor_raw = packet.tcp_sequence
        delta = (packet.tcp_sequence - anchor_raw + (1 << 31)) % (1 << 32) - (
            1 << 31
        )
        sequence = anchor_raw + delta
        existing = segments.get(sequence)
        if existing is not None:
            shared = min(len(existing), len(packet.payload))
            if existing[:shared] != packet.payload[:shared]:
                raise ValueError(
                    f"{role} {gate_id} {direction} TCP retransmission conflicts"
                )
            if len(packet.payload) <= len(existing):
                continue
        segments[sequence] = packet.payload
    if not segments:
        raise ValueError(f"{role} {gate_id} {direction} TCP flow has no payload")
    stream = bytearray()
    stream_start: int | None = None
    for sequence, payload in sorted(segments.items()):
        if stream_start is None:
            stream_start = sequence
            stream.extend(payload)
            continue
        offset = sequence - stream_start
        if offset < 0:
            raise ValueError(f"{role} {gate_id} {direction} TCP sequence wrapped")
        if offset > len(stream):
            raise ValueError(f"{role} {gate_id} {direction} TCP payload has a gap")
        overlap = min(len(payload), len(stream) - offset)
        if bytes(stream[offset : offset + overlap]) != payload[:overlap]:
            raise ValueError(f"{role} {gate_id} {direction} TCP overlap conflicts")
        stream.extend(payload[overlap:])
    return bytes(stream)


def _socks_address_end(stream: bytes, offset: int, context: str) -> tuple[int, bytes, int]:
    if offset >= len(stream):
        raise ValueError(f"{context} SOCKS address is truncated")
    address_type = stream[offset]
    offset += 1
    if address_type == 1:
        end = offset + 4
    elif address_type == 4:
        end = offset + 16
    elif address_type == 3:
        if offset >= len(stream):
            raise ValueError(f"{context} SOCKS domain is truncated")
        end = offset + 1 + stream[offset]
        offset += 1
    else:
        raise ValueError(f"{context} SOCKS address type is invalid")
    if end + 2 > len(stream):
        raise ValueError(f"{context} SOCKS address is truncated")
    address = stream[offset:end]
    port = int.from_bytes(stream[end : end + 2], "big")
    return end + 2, address, port


def _strip_socks_prefix(
    stream: bytes,
    *,
    role: str,
    gate_id: str,
    direction: str,
    target: tuple[bytes, int] | None,
) -> bytes:
    context = f"{role} {gate_id} {direction}"
    if direction == "outbound":
        if len(stream) < 3 or stream[0] != 5 or stream[1] < 1:
            raise ValueError(f"{context} SOCKS greeting is malformed")
        offset = 2 + stream[1]
        if offset > len(stream) or 0 not in stream[2:offset]:
            raise ValueError(f"{context} SOCKS greeting does not offer no-auth")
        if offset + 4 > len(stream) or stream[offset : offset + 3] != b"\x05\x01\x00":
            raise ValueError(f"{context} SOCKS CONNECT request is malformed")
        address_type = stream[offset + 3]
        end, address, port = _socks_address_end(stream, offset + 3, context)
        assert target is not None
        if address_type not in (1, 4) or address != target[0] or port != target[1]:
            raise ValueError(f"{context} SOCKS CONNECT target mismatch")
    else:
        if len(stream) < 6 or stream[:2] != b"\x05\x00" or stream[2:5] != b"\x05\x00\x00":
            raise ValueError(f"{context} SOCKS responses are malformed")
        end, _address, _port = _socks_address_end(stream, 5, context)
    if end >= len(stream):
        raise ValueError(f"{context} SOCKS stream has no TLS payload")
    return stream[end:]


def _tls_record_digest(
    stream: bytes,
    role: str,
    gate_id: str,
    direction: str,
    *,
    socks_direction: str | None,
    socks_target: tuple[bytes, int] | None,
) -> TlsStreamProof:
    if socks_direction is not None:
        stream = _strip_socks_prefix(
            stream,
            role=role,
            gate_id=gate_id,
            direction=socks_direction,
            target=socks_target,
        )
    records: list[bytes] = []
    application_lengths: list[int] = []
    offset = 0
    while offset < len(stream):
        if offset + 5 > len(stream):
            raise ValueError(
                f"{role} {gate_id} {direction} TLS stream has trailing bytes"
            )
        content_type = stream[offset]
        major, minor = stream[offset + 1], stream[offset + 2]
        length = int.from_bytes(stream[offset + 3 : offset + 5], "big")
        end = offset + 5 + length
        if (
            content_type in range(20, 24)
            and major == 3
            and minor in range(0, 5)
            and length in range(1, 18_433)
            and end <= len(stream)
        ):
            record = stream[offset:end]
            records.append(record)
            if content_type == 23:
                application_lengths.append(length)
            offset = end
            continue
        raise ValueError(
            f"{role} {gate_id} {direction} TLS stream contains invalid or partial framing"
        )
    if not records:
        raise ValueError(f"{role} {gate_id} {direction} flow has no complete TLS record")
    return TlsStreamProof(
        digest=hashlib.sha256(
            b"ripdpi:dns-evidence-tls-stream:v2:" + stream
        ).digest(),
        application_data_lengths=tuple(application_lengths),
    )


def _normalized_flow_digest(
    packets: list[Packet], outbound: list[int], inbound: list[int]
) -> bytes:
    entries = sorted(
        [(index, 0) for index in outbound] + [(index, 1) for index in inbound],
        key=lambda item: (packets[item[0]].timestamp_ns, item[0]),
    )
    base_timestamp = packets[entries[0][0]].timestamp_ns
    anchors: dict[int, int] = {}
    digest = hashlib.sha256(b"ripdpi:dns-evidence-normalized-flow:v1")
    for index, direction in entries:
        packet = packets[index]
        assert packet.tcp_sequence is not None
        anchor = anchors.setdefault(direction, packet.tcp_sequence)
        relative_sequence = (
            packet.tcp_sequence - anchor + (1 << 31)
        ) % (1 << 32) - (1 << 31)
        payload = packet.payload or b""
        digest.update(bytes((direction,)))
        digest.update(struct.pack("!q", packet.timestamp_ns - base_timestamp))
        digest.update(struct.pack("!qI", relative_sequence, len(payload)))
        digest.update(hashlib.sha256(payload).digest())
    return digest.digest()


def _resolver_endpoint_sha256(packet: Packet) -> str:
    assert (
        packet.destination_address is not None and packet.destination_port is not None
    )
    return hashlib.sha256(
        b"ripdpi:dns-evidence-resolver:v1:"
        + packet.destination_address
        + struct.pack("!H", packet.destination_port)
    ).hexdigest()


def _resolver_address_sha256(packet: Packet) -> str:
    assert packet.destination_address is not None
    return hashlib.sha256(
        b"ripdpi:dns-evidence-address:v1:" + packet.destination_address
    ).hexdigest()


def _require_dns_rule_packets(
    *,
    role: str,
    packets: list[Packet],
    interval: tuple[int, int],
    gate_id: str,
    rule_name: str,
    facts: dict[str, Any],
    fixture: FixtureContext,
) -> tuple[bytes, set[int]]:
    expected_by_field = {
        field: value
        for field, value in facts.items()
        if field.lower().endswith("querysha256")
    }
    expected = set(expected_by_field.values())
    if not expected or any(
        not isinstance(value, str) or SHA256_RE.fullmatch(value) is None
        for value in expected
    ):
        raise ValueError(f"{gate_id} receipt does not bind an exact DNS query set")
    start, finish = interval
    queries: dict[str, list[tuple[int, DnsPacketFacts, Packet]]] = {
        digest: [] for digest in expected
    }
    responses: dict[bytes, list[tuple[int, DnsPacketFacts, Packet]]] = {}
    allowed: set[int] = set()
    for record_index in range(start, finish + 1):
        packet = packets[record_index]
        if packet.payload is None or packet.transport != "udp":
            continue
        if packet.source_port == 53 or packet.destination_port == 53:
            continue
        try:
            dns = _dns_packet_facts(packet.payload)
        except ValueError:
            continue
        digest = _dns_evidence_query_sha256(dns.question_labels)
        if digest not in expected:
            continue
        if dns.is_response:
            responses.setdefault(dns.identity, []).append((record_index, dns, packet))
        else:
            queries[digest].append((record_index, dns, packet))
    for digest, occurrences in queries.items():
        if len(occurrences) != 1:
            raise ValueError(
                f"{role} {gate_id} must contain exactly one packet-parsed query for each receipt digest"
            )
        query_index, query, packet = occurrences[0]
        if query.is_response or query.rcode != 0 or query.answer_records:
            raise ValueError(
                f"{role} {gate_id} packet-parsed DNS query flags are invalid"
            )
        expected_endpoint_address = (
            SOURCE_OWNED_AUTHORITATIVE_IPV6
            if packet.ip_version == 6
            else fixture.address
        )
        if (
            packet.destination_address != expected_endpoint_address
            or packet.destination_port != fixture.dns_udp_port
        ):
            raise ValueError(
                f"{role} {gate_id} query used an unbound resolver/authoritative endpoint"
            )
        query_field = next(
            field for field, value in expected_by_field.items() if value == digest
        )
        resolver_fields = {
            "virtualQuerySha256": "tunnelResolverProviderSha256",
            "proxiedQuerySha256": (
                "tunnelResolverSha256"
                if "tunnelResolverSha256" in facts
                else "resolverProviderSha256"
            ),
            "directQuerySha256": (
                "directResolverSha256" if "directResolverSha256" in facts else None
            ),
            "bootstrapQuerySha256": "observedBootstrapResolverSha256",
            "outageQuerySha256": "encryptedResolverProviderSha256",
            "crashQuerySha256": "encryptedResolverProviderSha256",
            "conflictQuerySha256": "privateDnsProviderSha256",
        }
        resolver_field = resolver_fields.get(query_field)
        if (
            resolver_field is not None
            and _resolver_endpoint_sha256(packet) != facts[resolver_field]
        ):
            raise ValueError(
                f"{role} {gate_id} query used the wrong packet-observed resolver/provider path"
            )
        if (
            query_field == "virtualQuerySha256"
            and _resolver_address_sha256(packet) != facts["virtualDnsAddressSha256"]
        ):
            raise ValueError(
                f"{role} {gate_id} query did not use the bound virtual DNS address"
            )
        if query_field == "bootstrapQuerySha256":
            expected_set = hashlib.sha256(
                b"ripdpi:dns-evidence-resolver-set:v1:"
                + _resolver_endpoint_sha256(packet).encode("ascii")
            ).hexdigest()
            if expected_set != facts["allowlistedResolverSetSha256"]:
                raise ValueError(
                    f"{role} {gate_id} bootstrap resolver is not in the packet-bound allowlist"
                )
        if query_field == "ipv6QuerySha256" and packet.ip_version != 6:
            raise ValueError(
                f"{role} {gate_id} authoritative IPv6 query has the wrong source class"
            )
        allowed.add(query_index)
        matching_responses = [
            response
            for response in responses.get(query.identity, [])
            if response[0] > query_index
            and response[1].rcode == 0
            and response[1].answer_records
            == ((query.question_labels, fixture.dns_answer_address),)
            and response[2].source_address == packet.destination_address
            and response[2].source_port == packet.destination_port
            and response[2].destination_address == packet.source_address
            and response[2].destination_port == packet.source_port
        ]
        fail_closed_rule = rule_name in {
            "encrypted-outage-fail-closed-v1",
            "core-crash-dns-fail-closed-v1",
            "android-private-dns-conflict-v1",
        }
        if fail_closed_rule:
            if matching_responses:
                raise ValueError(
                    f"{role} {gate_id} unexpectedly contains a successful DNS response"
                )
        else:
            if len(matching_responses) != 1:
                raise ValueError(
                    f"{role} {gate_id} is missing an exact packet-parsed DNS response"
                )
            allowed.add(matching_responses[0][0])
    proof_material = b"\0".join(sorted(value.encode("ascii") for value in expected))
    return hashlib.sha256(
        b"ripdpi:dns-evidence-packet-proof:v1:"
        + gate_id.encode("ascii")
        + b":"
        + proof_material
    ).digest(), allowed


def derive_observation(
    *,
    role: str,
    capture: Path,
    expected_digest: str,
    metadata: CaptureMetadata,
    plan: dict[str, Any],
    windows: list[Window],
    semantic_rules: dict[str, SemanticRule],
    fixture: FixtureContext | None,
    startup_query_sha256: str | None,
    startup_exchange_count: int | None,
    dns_action_facts: dict[str, dict[str, Any]],
    fixture_transcript_proofs: dict[str, EncryptedDnsTranscriptProof],
) -> tuple[dict[str, Any], dict[str, bytes | EncryptedDnsRoleProof]]:
    counters = {
        window.identifier: {
            "expected": 0,
            "unexpected": 0,
            "errors": 0,
            "action": 0,
            "outcome": 0,
        }
        for window in windows
    }
    marker_owners = {
        digest.encode("ascii"): (window.identifier, phase)
        for window in windows
        for digest, phase in (
            (window.action_sha256, "action"),
            (window.outcome_sha256, "outcome"),
        )
    }
    packets = list(iter_classic_pcap(capture, expected_digest))
    if len(packets) != metadata.packet_count:
        raise ValueError(
            f"{role} parsed packet count does not match private capture metadata"
        )
    intervals = _locate_marker_intervals(
        role=role,
        packets=packets,
        windows=windows,
        semantic_rules=semantic_rules,
        marker_owners=marker_owners,
    )
    for window in windows:
        if semantic_rules[window.identifier].name == STARTUP_RULE:
            counters[window.identifier]["action"] = 1
            counters[window.identifier]["outcome"] = 1
    semantic_proofs: dict[str, bytes | EncryptedDnsRoleProof] = {}
    allowed_dns_records: dict[str, set[int]] = {}
    for index, window in enumerate(windows):
        if semantic_rules[window.identifier].name != STARTUP_RULE:
            continue
        if fixture is None:
            raise ValueError("startup semantic rule requires a fixture manifest")
        if startup_query_sha256 is None:
            raise ValueError("startup semantic rule requires a bound DNS query digest")
        if startup_exchange_count is None:
            raise ValueError("startup semantic rule requires a bound DNS exchange count")
        proof, records = _require_receipt_bound_dns(
            role=role,
            packets=packets,
            fixture=fixture,
            expected_query_sha256=startup_query_sha256,
            expected_exchange_count=startup_exchange_count,
        )
        start, finish = intervals[index]
        if any(record < start or record > finish for record in records):
            raise ValueError(
                f"{role} receipt-bound fixture DNS exchange is outside the startup marker window"
            )
        semantic_proofs[window.identifier] = proof
        allowed_dns_records[window.identifier] = records
    for index, window in enumerate(windows):
        rule = semantic_rules[window.identifier]
        if rule.name not in DNS_RULES.values():
            continue
        facts = dns_action_facts.get(window.identifier)
        if facts is None:
            raise ValueError(f"{window.identifier} requires its exact action receipt")
        if rule.name in SOURCE_OWNED_ENCRYPTED_DNS_RULES:
            if fixture is None or window.identifier not in fixture_transcript_proofs:
                raise ValueError(
                    f"{window.identifier} requires a bound fixture transcript"
                )
            proof, records = _require_encrypted_dns_role_packets(
                role=role,
                packets=packets,
                interval=intervals[index],
                gate_id=window.identifier,
                fixture=fixture,
                transcript_proof=fixture_transcript_proofs[window.identifier],
            )
        else:
            proof, records = _require_dns_rule_packets(
                role=role,
                packets=packets,
                interval=intervals[index],
                gate_id=window.identifier,
                rule_name=rule.name,
                facts=facts,
                fixture=fixture,
            )
        semantic_proofs[window.identifier] = proof
        allowed_dns_records[window.identifier] = records
    interval_starts = [start for start, _finish in intervals]
    for record_index, packet in enumerate(packets):
        interval_index = bisect.bisect_right(interval_starts, record_index) - 1
        if interval_index < 0 or record_index > intervals[interval_index][1]:
            continue
        window = windows[interval_index]
        rule = semantic_rules[window.identifier]
        if packet.payload is None:
            counters[window.identifier]["errors"] += 1
            continue
        if len(packet.payload) > MAX_PACKET_BYTES:
            raise ValueError("transport payload exceeds the scan bound")
        matches = _known_marker_matches(packet.payload, marker_owners)
        if rule.name == STARTUP_RULE:
            if fixture is None:
                raise ValueError("startup semantic rule requires a fixture manifest")
            if record_index in allowed_dns_records[window.identifier]:
                counters[window.identifier]["expected"] += 1
                continue
            if packet.transport == "tcp" and _is_fixture_packet(
                packet, fixture, fixture.control_port
            ):
                counters[window.identifier]["expected"] += 1
                for owner_id, phase in matches:
                    if owner_id != window.identifier:
                        raise ValueError(
                            f"{role} marker appears in the wrong ledger window"
                        )
                    counters[owner_id][phase] = 1
                continue
            counters[window.identifier]["unexpected"] += 1
            continue
        if rule.name in DNS_RULES.values():
            if record_index in allowed_dns_records[window.identifier]:
                counters[window.identifier]["expected"] += 1
                continue
            if matches:
                if len(matches) != 1:
                    raise ValueError(
                        f"{role} packet contains duplicate or multiple markers"
                    )
                owner_id, phase = matches[0]
                if owner_id != window.identifier:
                    raise ValueError(
                        f"{role} marker appears in the wrong ledger window"
                    )
                counters[owner_id]["expected"] += 1
                counters[owner_id][phase] += 1
                continue
            counters[window.identifier]["unexpected"] += 1
            continue
        if not matches:
            counters[window.identifier]["unexpected"] += 1
            continue
        if len(matches) != 1:
            raise ValueError(f"{role} packet contains duplicate or multiple markers")
        owner_id, phase = matches[0]
        if owner_id != window.identifier:
            raise ValueError(f"{role} marker appears in the wrong ledger window")
        counters[owner_id]["expected"] += 1
        counters[owner_id][phase] += 1

    observation_windows: list[dict[str, Any]] = []
    for window in windows:
        count = counters[window.identifier]
        if count["action"] != 1 or count["outcome"] != 1:
            raise ValueError(
                f"{role} window {window.identifier} must contain exactly one action "
                "and one outcome marker"
            )
        if (
            semantic_rules[window.identifier].name in DNS_RULES.values()
            and count["unexpected"] > 0
        ):
            raise ValueError(
                f"{role} DNS gate {window.identifier} contains an unexpected or leak packet"
            )
        observation_windows.append(
            {
                "id": window.identifier,
                "kind": window.kind,
                "startedAtEpoch": window.started,
                "finishedAtEpoch": window.finished,
                "expectedPacketCount": count["expected"],
                "unexpectedPacketCount": count["unexpected"],
                "captureErrorCount": count["errors"],
                "actionMarkerSha256": window.action_sha256,
                "outcomeMarkerSha256": window.outcome_sha256,
                "actionObservedCount": count["action"],
                "outcomeObservedCount": count["outcome"],
            }
        )
    return {
        "version": OBSERVATION_VERSION,
        "sourceSha": plan["sourceSha"],
        "correlationId": plan["correlationId"],
        "role": role,
        "clientArtifactSha256": plan["clientArtifactSha256"],
        "testArtifactSha256": plan["testArtifactSha256"],
        "scenarioPlanSha256": hashlib.sha256(canonical_json_bytes(plan)).hexdigest(),
        "captureStartedAtEpoch": metadata.started,
        "captureFinishedAtEpoch": metadata.finished,
        "rawCaptureSha256": expected_digest,
        "windows": observation_windows,
    }, semantic_proofs


def write_private_json(path: Path, value: dict[str, Any]) -> None:
    if path.is_symlink():
        raise ValueError(f"refusing symlink output: {path}")
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(canonical_json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        temporary.unlink(missing_ok=True)


def validate_output_location(path: Path) -> None:
    parent = path.parent
    if parent.is_symlink() or not parent.is_dir():
        raise ValueError(
            f"output parent must be an existing non-symlink directory: {parent}"
        )
    parent_stat = parent.stat()
    if parent_stat.st_uid != os.geteuid() or parent_stat.st_mode & 0o077:
        raise ValueError(f"output parent must be private and caller-owned: {parent}")
    try:
        target_stat = path.lstat()
    except FileNotFoundError:
        return
    if stat.S_ISLNK(target_stat.st_mode):
        raise ValueError(f"refusing symlink output: {path}")
    if not stat.S_ISREG(target_stat.st_mode):
        raise ValueError(f"output must be a regular file path: {path}")
    if target_stat.st_uid != os.geteuid():
        raise ValueError(f"existing output must be caller-owned: {path}")
    if target_stat.st_nlink != 1:
        raise ValueError(f"existing output must not be hard-linked: {path}")


def validate_final_output(path: Path) -> None:
    target_stat = path.lstat()
    if (
        not stat.S_ISREG(target_stat.st_mode)
        or target_stat.st_uid != os.geteuid()
        or target_stat.st_nlink != 1
        or target_stat.st_mode & 0o777 != 0o600
    ):
        raise ValueError(
            f"final output is not a private caller-owned mode-0600 file: {path}"
        )


def remove_output(path: Path) -> None:
    """Remove only a regular output path, never a symlink or directory target."""
    validate_output_location(path)
    if path.exists():
        path.unlink()


def paths_are_same_file(first: Path, second: Path) -> bool:
    try:
        return os.path.samefile(first, second)
    except FileNotFoundError:
        return False


def reject_existing_aliases(paths: tuple[Path, ...]) -> None:
    for index, first in enumerate(paths):
        for second in paths[index + 1 :]:
            if paths_are_same_file(first, second):
                raise ValueError(
                    f"paths must not alias the same inode: {first} and {second}"
                )


def _validate_dual_vantage_semantic_proofs(
    client: dict[str, bytes | EncryptedDnsRoleProof],
    observer: dict[str, bytes | EncryptedDnsRoleProof],
) -> None:
    if set(client) != set(observer):
        raise ValueError("dual-vantage DNS semantic proof inventory mismatch")
    for gate_id in client:
        client_proof = client[gate_id]
        observer_proof = observer[gate_id]
        if isinstance(client_proof, EncryptedDnsRoleProof) or isinstance(
            observer_proof, EncryptedDnsRoleProof
        ):
            if not isinstance(
                client_proof, EncryptedDnsRoleProof
            ) or not isinstance(observer_proof, EncryptedDnsRoleProof):
                raise ValueError(f"{gate_id} dual-vantage proof type mismatch")
            if (
                client_proof.transcript_digest != observer_proof.transcript_digest
                or client_proof.target != observer_proof.target
                or client_proof.outbound_tls_digest
                != observer_proof.outbound_tls_digest
                or client_proof.inbound_tls_digest
                != observer_proof.inbound_tls_digest
            ):
                raise ValueError(
                    f"{gate_id} client-underlay and observer TLS flow mapping mismatch"
                )
            if client_proof.source == observer_proof.source:
                raise ValueError(
                    f"{gate_id} role captures reuse one copied transcript-bound 5-tuple"
                )
            if (
                client_proof.normalized_flow_digest
                == observer_proof.normalized_flow_digest
            ):
                raise ValueError(
                    f"{gate_id} role captures reuse one tuple-independent normalized flow"
                )
            if (
                client_proof.packet_timestamps_ns
                == observer_proof.packet_timestamps_ns
            ):
                raise ValueError(
                    f"{gate_id} role captures reuse one copied transcript-bound flow"
                )
        elif client_proof != observer_proof:
            raise ValueError("dual-vantage DNS semantic proofs do not match")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-pcap", required=True, type=Path)
    parser.add_argument("--observer-pcap", required=True, type=Path)
    parser.add_argument("--client-metadata", required=True, type=Path)
    parser.add_argument("--observer-metadata", required=True, type=Path)
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--action-receipt", type=Path, action="append", default=[])
    parser.add_argument("--fixture-transcript", type=Path, action="append", default=[])
    parser.add_argument("--test-only-action-registry-override", type=Path)
    parser.add_argument("--fixture-manifest", type=Path)
    parser.add_argument("--client-output", required=True, type=Path)
    parser.add_argument("--observer-output", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        paths = (
            args.client_pcap,
            args.observer_pcap,
            args.client_metadata,
            args.observer_metadata,
            args.ledger,
            *args.action_receipt,
            *args.fixture_transcript,
            *(path for path in (args.test_only_action_registry_override,) if path),
            *(path for path in (args.fixture_manifest,) if path),
            args.client_output,
            args.observer_output,
        )
        resolved = [path.resolve(strict=False) for path in paths]
        if len(set(resolved)) != len(resolved):
            raise ValueError("input and output paths must not alias")
        reject_existing_aliases(paths)
        validate_output_location(args.client_output)
        validate_output_location(args.observer_output)
        remove_output(args.client_output)
        remove_output(args.observer_output)
        ledger, _raw = load_strict_json(
            args.ledger, "action ledger", maximum_bytes=MAX_LEDGER_BYTES
        )
        plan, windows, digests, semantic_rules = validate_ledger(
            ledger, forbidden_generic_ids=load_android_dual_vantage_gate_ids()
        )
        action_rules = [
            rule
            for rule in semantic_rules.values()
            if rule.name == STARTUP_RULE or rule.name in DNS_RULES.values()
        ]
        fixture: FixtureContext | None = None
        startup_query_sha256: str | None = None
        startup_exchange_count: int | None = None
        dns_action_facts: dict[str, dict[str, Any]] = {}
        fixture_transcript_proofs: dict[str, EncryptedDnsTranscriptProof] = {}
        if action_rules:
            if (
                len(args.action_receipt) != len(action_rules)
                or args.fixture_manifest is None
            ):
                raise ValueError(
                    "every action semantic rule requires one ordered --action-receipt and a fixture manifest"
                )
            fixture_identities = {rule.fixture_identity_sha256 for rule in action_rules}
            if None in fixture_identities or len(fixture_identities) != 1:
                raise ValueError(
                    "action semantic rules must bind one exact fixture identity"
                )
            fixture_identity = next(iter(fixture_identities))
            assert fixture_identity is not None
            fixture = load_fixture_context(args.fixture_manifest, fixture_identity)
            for action_rule, receipt_path in zip(
                action_rules, args.action_receipt, strict=True
            ):
                assert action_rule.action_receipt_sha256 is not None
                _query_set_sha256, action_facts = validate_action_receipt(
                    receipt_path,
                    expected_digest=action_rule.action_receipt_sha256,
                    fixture_identity_sha256=fixture_identity,
                    plan=plan,
                    gate_id=action_rule.window_id,
                    test_only_ready_override=args.test_only_action_registry_override,
                )
                if action_rule.name == STARTUP_RULE:
                    startup_query_sha256 = require_pattern(
                        action_facts["dnsQuerySha256"],
                        SHA256_RE,
                        "receipt.facts.dnsQuerySha256",
                    )
                    startup_exchange_count = action_facts["postReadyDnsEventCount"]
                else:
                    dns_action_facts[action_rule.window_id] = action_facts
            transcript_rules = [
                rule
                for rule in action_rules
                if rule.name in SOURCE_OWNED_ENCRYPTED_DNS_RULES
            ]
            if len(args.fixture_transcript) != len(transcript_rules):
                raise ValueError(
                    "every source-owned encrypted DNS action requires one ordered --fixture-transcript"
                )
            for action_rule, transcript_path in zip(
                transcript_rules, args.fixture_transcript, strict=True
            ):
                fixture_transcript_proofs[action_rule.window_id] = (
                    _load_fixture_transcript(
                        transcript_path,
                        gate_id=action_rule.window_id,
                        plan=plan,
                        fixture=fixture,
                        facts=dns_action_facts[action_rule.window_id],
                    )
                )
        elif (
            args.action_receipt
            or args.fixture_transcript
            or args.fixture_manifest is not None
            or args.test_only_action_registry_override is not None
        ):
            raise ValueError(
                "action receipt, fixture transcript, and fixture manifest are forbidden without an action semantic rule"
            )
        client_metadata_value, _raw = load_strict_json(
            args.client_metadata,
            "client-underlay capture metadata",
            maximum_bytes=MAX_METADATA_BYTES,
        )
        observer_metadata_value, _raw = load_strict_json(
            args.observer_metadata,
            "external-observer capture metadata",
            maximum_bytes=MAX_METADATA_BYTES,
        )
        client_metadata = validate_capture_metadata(
            client_metadata_value,
            expected_role="client-underlay",
            expected_correlation_id=plan["correlationId"],
        )
        observer_metadata = validate_capture_metadata(
            observer_metadata_value,
            expected_role="external-observer",
            expected_correlation_id=plan["correlationId"],
        )
        for metadata in (client_metadata, observer_metadata):
            if metadata.raw_capture_sha256 != digests[metadata.role]:
                raise ValueError(
                    f"{metadata.role} metadata digest does not match ledger"
                )
            if any(
                window.started < metadata.started or window.finished > metadata.finished
                for window in windows
            ):
                raise ValueError(
                    f"{metadata.role} actual capture bounds do not enclose every plan window"
                )
        client, client_proofs = derive_observation(
            role="client-underlay",
            capture=args.client_pcap,
            expected_digest=digests["client-underlay"],
            metadata=client_metadata,
            plan=plan,
            windows=windows,
            semantic_rules=semantic_rules,
            fixture=fixture,
            startup_query_sha256=startup_query_sha256,
            startup_exchange_count=startup_exchange_count,
            dns_action_facts=dns_action_facts,
            fixture_transcript_proofs=fixture_transcript_proofs,
        )
        observer, observer_proofs = derive_observation(
            role="external-observer",
            capture=args.observer_pcap,
            expected_digest=digests["external-observer"],
            metadata=observer_metadata,
            plan=plan,
            windows=windows,
            semantic_rules=semantic_rules,
            fixture=fixture,
            startup_query_sha256=startup_query_sha256,
            startup_exchange_count=startup_exchange_count,
            dns_action_facts=dns_action_facts,
            fixture_transcript_proofs=fixture_transcript_proofs,
        )
        _validate_dual_vantage_semantic_proofs(client_proofs, observer_proofs)
        write_private_json(args.client_output, client)
        write_private_json(args.observer_output, observer)
        validate_final_output(args.client_output)
        validate_final_output(args.observer_output)
    except (OSError, ValueError) as error:
        for output in (args.client_output, args.observer_output):
            try:
                remove_output(output)
            except (OSError, ValueError):
                pass
        print(f"network evidence PCAP oracle failed: {error}", file=os.sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
