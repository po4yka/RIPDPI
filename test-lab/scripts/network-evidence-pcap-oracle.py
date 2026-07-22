#!/usr/bin/env python3
"""Derive redacted observations from two source-owned classic PCAP captures.

The action ledger and raw captures are private inputs.  This oracle recognizes
only the generic marker-pair seam; it deliberately does not infer gate-specific
DNS, routing, leak, or kill-switch semantics from packet presence.
"""

from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import os
import re
import stat
import struct
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterator


LEDGER_VERSION = "network_evidence_action_ledger_v1"
PLAN_VERSION = "network_evidence_scenario_plan_v2"
OBSERVATION_VERSION = "network_evidence_observation_v2"
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
MAX_WINDOWS = 64

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
SEMANTIC_RULE_FIELDS = {"windowId", "rule"}
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
    record_index: int
    timestamp_ns: int


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
) -> tuple[dict[str, Any], list[Window], dict[str, str]]:
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
    rule_ids: set[str] = set()
    for index, rule in enumerate(rules):
        context = f"semanticRules[{index}]"
        if not isinstance(rule, dict):
            raise ValueError(f"{context} must be an object")
        require_exact_fields(rule, SEMANTIC_RULE_FIELDS, context)
        window_id = require_pattern(
            rule["windowId"], IDENTIFIER_RE, f"{context}.windowId"
        )
        if window_id in rule_ids:
            raise ValueError(f"semanticRules has duplicate window id: {window_id}")
        if rule["rule"] != "generic-marker-pair":
            raise ValueError(
                f"unsupported semantic rule for {window_id}; "
                "the ledger does not prove a source-owned gate semantic seam"
            )
        if window_id in forbidden_generic_ids:
            raise ValueError(
                f"generic-marker-pair is forbidden for Android dual-vantage gate {window_id}; "
                "a source-owned semantic action seam is required"
            )
        rule_ids.add(window_id)
    if rule_ids != seen_ids:
        raise ValueError("semanticRules must prove generic semantics for every window")

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
    return plan, windows, digests


def _parse_ethernet_payload(frame: bytes) -> bytes:
    if len(frame) < 14:
        raise ValueError("truncated Ethernet frame")
    ether_type = struct.unpack_from("!H", frame, 12)[0]
    return _parse_ethertype_payload(ether_type, frame[14:])


def _parse_ethertype_payload(protocol: int, packet: bytes) -> bytes:
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


def _parse_raw_ip_payload(packet: bytes) -> bytes:
    if not packet:
        raise ValueError("truncated raw IP packet")
    version = packet[0] >> 4
    if version == 4:
        return _parse_ipv4_payload(packet)
    if version == 6:
        return _parse_ipv6_payload(packet)
    raise ValueError("raw capture packet has an unsupported IP version")


def _parse_linux_sll_payload(packet: bytes) -> bytes:
    if len(packet) < 16:
        raise ValueError("truncated Linux cooked SLL header")
    address_length = struct.unpack_from("!H", packet, 4)[0]
    if address_length > 8:
        raise ValueError("invalid Linux cooked SLL address length")
    protocol = struct.unpack_from("!H", packet, 14)[0]
    return _parse_ethertype_payload(protocol, packet[16:])


def _parse_linux_sll2_payload(packet: bytes) -> bytes:
    if len(packet) < 20:
        raise ValueError("truncated Linux cooked SLL2 header")
    protocol, reserved = struct.unpack_from("!HH", packet, 0)
    address_length = packet[11]
    if reserved != 0 or address_length > 8:
        raise ValueError("invalid Linux cooked SLL2 header")
    return _parse_ethertype_payload(protocol, packet[20:])


def _parse_ipv4_payload(packet: bytes) -> bytes:
    if len(packet) < 20 or packet[0] >> 4 != 4:
        raise ValueError("truncated or malformed IPv4 packet")
    header_length = (packet[0] & 0x0F) * 4
    total_length = struct.unpack_from("!H", packet, 2)[0]
    fragment = struct.unpack_from("!H", packet, 6)[0]
    if header_length < 20 or total_length < header_length or total_length > len(packet):
        raise ValueError("invalid IPv4 length")
    if fragment & 0x3FFF:
        raise ValueError("fragmented IPv4 packets are not a supported oracle seam")
    return _parse_transport_payload(packet[9], packet[header_length:total_length])


def _parse_ipv6_payload(packet: bytes) -> bytes:
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
    return _parse_transport_payload(next_header, packet[offset:end])


def _parse_transport_payload(protocol: int, segment: bytes) -> bytes:
    if protocol == 6:
        if len(segment) < 20:
            raise ValueError("truncated TCP segment")
        header_length = (segment[12] >> 4) * 4
        if header_length < 20 or header_length > len(segment):
            raise ValueError("invalid TCP header length")
        return segment[header_length:]
    if protocol == 17:
        if len(segment) < 8:
            raise ValueError("truncated UDP datagram")
        udp_length = struct.unpack_from("!H", segment, 4)[0]
        if udp_length < 8 or udp_length > len(segment):
            raise ValueError("invalid UDP length")
        return segment[8:udp_length]
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
            yield Packet(timestamp_ns, packet_parser(frame))


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
    capture: Path,
    expected_digest: str,
    metadata: CaptureMetadata,
    windows: list[Window],
    marker_owners: dict[bytes, tuple[str, str]],
) -> list[tuple[int, int]]:
    positions: dict[tuple[str, str], MarkerPosition] = {}
    packet_count = 0
    for record_index, packet in enumerate(iter_classic_pcap(capture, expected_digest)):
        packet_count += 1
        if packet.payload is None:
            continue
        matches = _known_marker_matches(packet.payload, marker_owners)
        if len(matches) > 1:
            raise ValueError(f"{role} packet contains duplicate or multiple markers")
        if matches:
            owner = matches[0]
            if owner in positions:
                raise ValueError(
                    f"{role} capture contains a duplicate {owner[1]} marker"
                )
            positions[owner] = MarkerPosition(record_index, packet.timestamp_ns)
    if packet_count != metadata.packet_count:
        raise ValueError(
            f"{role} parsed packet count does not match private capture metadata"
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
            action.record_index >= outcome.record_index
            or action.timestamp_ns > outcome.timestamp_ns
        ):
            raise ValueError(
                f"{role} window {window.identifier} action marker must precede outcome marker"
            )
        if previous_outcome is not None and (
            previous_outcome.record_index >= action.record_index
            or previous_outcome.timestamp_ns > action.timestamp_ns
        ):
            raise ValueError(
                f"{role} marker intervals must follow plan order without overlap"
            )
        intervals.append((action.record_index, outcome.record_index))
        previous_outcome = outcome
    return intervals


def derive_observation(
    *,
    role: str,
    capture: Path,
    expected_digest: str,
    metadata: CaptureMetadata,
    plan: dict[str, Any],
    windows: list[Window],
) -> dict[str, Any]:
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
    intervals = _locate_marker_intervals(
        role=role,
        capture=capture,
        expected_digest=expected_digest,
        metadata=metadata,
        windows=windows,
        marker_owners=marker_owners,
    )
    interval_starts = [start for start, _finish in intervals]
    packet_count = 0
    for record_index, packet in enumerate(iter_classic_pcap(capture, expected_digest)):
        packet_count += 1
        interval_index = bisect.bisect_right(interval_starts, record_index) - 1
        if interval_index < 0 or record_index > intervals[interval_index][1]:
            continue
        window = windows[interval_index]
        if packet.payload is None:
            counters[window.identifier]["errors"] += 1
            continue
        if len(packet.payload) > MAX_PACKET_BYTES:
            raise ValueError("transport payload exceeds the scan bound")
        matches = _known_marker_matches(packet.payload, marker_owners)
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

    if packet_count != metadata.packet_count:
        raise ValueError(
            f"{role} parsed packet count does not match private capture metadata"
        )

    observation_windows: list[dict[str, Any]] = []
    for window in windows:
        count = counters[window.identifier]
        if count["action"] != 1 or count["outcome"] != 1:
            raise ValueError(
                f"{role} window {window.identifier} must contain exactly one action "
                "and one outcome marker"
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
    }


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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--client-pcap", required=True, type=Path)
    parser.add_argument("--observer-pcap", required=True, type=Path)
    parser.add_argument("--client-metadata", required=True, type=Path)
    parser.add_argument("--observer-metadata", required=True, type=Path)
    parser.add_argument("--ledger", required=True, type=Path)
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
        plan, windows, digests = validate_ledger(
            ledger, forbidden_generic_ids=load_android_dual_vantage_gate_ids()
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
        client = derive_observation(
            role="client-underlay",
            capture=args.client_pcap,
            expected_digest=digests["client-underlay"],
            metadata=client_metadata,
            plan=plan,
            windows=windows,
        )
        observer = derive_observation(
            role="external-observer",
            capture=args.observer_pcap,
            expected_digest=digests["external-observer"],
            metadata=observer_metadata,
            plan=plan,
            windows=windows,
        )
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
