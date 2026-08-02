#!/usr/bin/env python3
"""Source-owned semantic oracles for Android ordinary release evidence.

The private receipt records individual device events and probe outcomes.  It is
not a verdict.  This module independently parses that receipt, the classic PCAP
captured at the owner-controlled fixture's public interface, and raw
route-command output before deriving a gate result.
"""

from __future__ import annotations

import hashlib
import ipaddress
import json
import re
import struct
from dataclasses import dataclass
from typing import Any


VERIFIER_VERSION = "android_ordinary_semantic_oracles_v2"
RECEIPT_VERSION = "android_ordinary_action_receipt_v1"
ROUTE_SNAPSHOT_VERSION = "android_ordinary_route_snapshot_v1"
MARKER_PREFIX = "RIPDPI-ORDINARY-V1"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
INTERFACE_RE = re.compile(r"[a-zA-Z0-9_.-]{1,32}\Z")
PACKAGE_RE = re.compile(r"com\.poyka\.ripdpi(?:\.simple)?\Z")
MAX_JSON_BYTES = 256 * 1024
MAX_PCAP_BYTES = 64 * 1024 * 1024
MAX_PCAP_PACKETS = 250_000
MAX_CAPTURE_CLOCK_OFFSET_MS = 5 * 60 * 1000
FORBIDDEN_ASSERTION_KEYS = {
    "count",
    "pass",
    "passed",
    "state",
    "status",
    "success",
    "verdict",
}


class OracleError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


@dataclass(frozen=True)
class ArtifactBytes:
    kind: str
    payload: bytes
    sha256: str


@dataclass(frozen=True)
class Packet:
    timestamp_ms: int
    ip_version: int
    source: str
    destination: str
    protocol: int
    source_port: int | None
    destination_port: int | None
    payload: bytes


@dataclass(frozen=True)
class ActionContext:
    action_id: str
    gate_ids: tuple[str, ...]
    correlation_id: str
    source_sha: str
    app_apk_sha256: str
    test_apk_sha256: str
    window_started_at_ms: int
    window_finished_at_ms: int
    artifacts: dict[str, ArtifactBytes]


EXPECTED_PROBES = {
    "ipv4-only": (
        ("ipv4-control", "ipv4", "connected"),
        ("ipv6-only", "ipv6", "blocked"),
    ),
    "dual-stack": (("ipv6-via-tunnel", "ipv6", "connected"),),
    "forced-revoke": (
        ("post-revoke-ipv4", "ipv4", "blocked"),
        ("post-revoke-ipv6", "ipv6", "blocked"),
    ),
    "core-fault": (
        ("transition-ipv4", "ipv4", "blocked"),
        ("transition-ipv6", "ipv6", "blocked"),
    ),
    "wifi-lte-switch": (
        ("post-switch-ipv4", "ipv4", "connected"),
    ),
    "sleep-wake": (
        ("post-wake-ipv4", "ipv4", "connected"),
    ),
    "android-always-on-block": (
        ("post-stop-ipv4", "ipv4", "connected"),
    ),
}

EXPECTED_PHASES = {
    "ipv4-only": ("steady",),
    "dual-stack": ("steady",),
    "forced-revoke": ("closed",),
    "core-fault": ("closed",),
    "wifi-lte-switch": ("protected",),
    "sleep-wake": ("protected",),
    "android-always-on-block": ("protected",),
}

TUNNEL_ACTIVITY_ACTIONS = {
    "ipv4-only",
    "dual-stack",
    "wifi-lte-switch",
    "sleep-wake",
    "android-always-on-block",
}


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode()


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID",
            f"{label} keys differ; missing={sorted(expected - set(value))}, "
            f"extra={sorted(set(value) - expected)}",
        )


def _strict_json(raw: bytes, *, label: str) -> dict[str, Any]:
    if not raw or len(raw) > MAX_JSON_BYTES:
        raise OracleError("SEMANTIC_SIZE_INVALID", f"{label} size is outside its bound")

    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise OracleError(
                    "SEMANTIC_SCHEMA_INVALID", f"{label} duplicates key {key!r}"
                )
            result[key] = value
        return result

    try:
        value = json.loads(raw, object_pairs_hook=reject_duplicates)
    except OracleError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID", f"{label} is not valid UTF-8 JSON"
        ) from error
    if not isinstance(value, dict):
        raise OracleError("SEMANTIC_SCHEMA_INVALID", f"{label} must be an object")
    if raw != canonical_json_bytes(value):
        raise OracleError("SEMANTIC_NONCANONICAL", f"{label} is not canonical JSON")
    return value


def _reject_assertion_fields(value: Any, *, label: str) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() in FORBIDDEN_ASSERTION_KEYS:
                raise OracleError(
                    "CALLER_VERDICT_FORBIDDEN",
                    f"{label} contains forbidden assertion field {key!r}",
                )
            _reject_assertion_fields(child, label=f"{label}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _reject_assertion_fields(child, label=f"{label}[{index}]")


def _digest(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise OracleError("SEMANTIC_SCHEMA_INVALID", f"{label} is malformed")
    return value


def _positive_int(value: Any, label: str, *, maximum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID", f"{label} must be a positive integer"
        )
    if maximum is not None and value > maximum:
        raise OracleError("SEMANTIC_SCHEMA_INVALID", f"{label} exceeds its bound")
    return value


def _ip(
    value: Any, label: str, *, version: int | None = None
) -> ipaddress._BaseAddress:
    if not isinstance(value, str):
        raise OracleError("SEMANTIC_SCHEMA_INVALID", f"{label} must be an IP address")
    try:
        address = ipaddress.ip_address(value)
    except ValueError as error:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID", f"{label} must be an IP address"
        ) from error
    if version is not None and address.version != version:
        raise OracleError("SEMANTIC_SCHEMA_INVALID", f"{label} must be IPv{version}")
    if address.is_unspecified or address.is_loopback or address.is_multicast:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID", f"{label} must be a routed unicast address"
        )
    return address


def _validate_fixture(value: Any, *, action_id: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID", "receipt.fixture must be an object"
        )
    _exact_keys(
        value,
        {
            "controlIpv4",
            "controlIpv6",
            "markerAddress",
            "markerPort",
            "probePort",
            "tunnelEndpoints",
            "tunnelPort",
        },
        "receipt.fixture",
    )
    marker = _ip(value["markerAddress"], "fixture.markerAddress", version=4)
    ipv4 = _ip(value["controlIpv4"], "fixture.controlIpv4", version=4)
    ipv6 = _ip(value["controlIpv6"], "fixture.controlIpv6", version=6)
    marker_port = _positive_int(
        value["markerPort"], "fixture.markerPort", maximum=65535
    )
    probe_port = _positive_int(value["probePort"], "fixture.probePort", maximum=65535)
    tunnel_port = _positive_int(
        value["tunnelPort"], "fixture.tunnelPort", maximum=65535
    )
    endpoints = value["tunnelEndpoints"]
    if not isinstance(endpoints, list) or not 1 <= len(endpoints) <= 8:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID",
            "fixture.tunnelEndpoints must contain 1..8 addresses",
        )
    parsed_endpoints = [
        _ip(endpoint, f"fixture.tunnelEndpoints[{index}]")
        for index, endpoint in enumerate(endpoints)
    ]
    endpoints = [
        (str(marker), marker_port),
        (str(ipv4), probe_port),
        (str(ipv6), probe_port),
        *((str(item), tunnel_port) for item in parsed_endpoints),
    ]
    if len(endpoints) != len(set(endpoints)):
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID",
            "fixture address-and-port endpoints must be distinct",
        )
    if action_id == "ipv4-only" and any(item.version != 4 for item in parsed_endpoints):
        raise OracleError(
            "SEMANTIC_IPV4_ONLY_INVALID", "IPv4-only tunnel endpoints must be IPv4"
        )
    return {
        "markerAddress": str(marker),
        "markerPort": marker_port,
        "controlIpv4": str(ipv4),
        "controlIpv6": str(ipv6),
        "probePort": probe_port,
        "tunnelEndpoints": tuple(str(item) for item in parsed_endpoints),
        "tunnelPort": tunnel_port,
    }


def _event_time(event: dict[str, Any], action_id: str) -> int:
    schemas: dict[str, tuple[set[str], str]] = {
        "ipv4-only": ({"kind", "mode", "observedAtEpochMs"}, "mode-applied"),
        "dual-stack": ({"kind", "mode", "observedAtEpochMs"}, "mode-applied"),
        "forced-revoke": (
            {"appOpMode", "kind", "observedAtEpochMs"},
            "vpn-permission-revoke-failed-closed-under-lockdown",
        ),
        "core-fault": (
            {"coreExitCode", "corePid", "kind", "observedAtEpochMs"},
            "native-core-exited",
        ),
        "wifi-lte-switch": (
            {
                "fromNetworkHandleSha256",
                "fromTransport",
                "kind",
                "observedAtEpochMs",
                "toNetworkHandleSha256",
                "toTransport",
            },
            "underlay-changed",
        ),
        "sleep-wake": ({"kind", "sleepAtEpochMs", "wakeAtEpochMs"}, "device-wake"),
        "android-always-on-block": (
            {"kind", "observedAtEpochMs", "packageName"},
            "vpn-stop-absorbed-under-lockdown",
        ),
    }
    expected_keys, expected_kind = schemas[action_id]
    _exact_keys(event, expected_keys, "receipt.event")
    if event["kind"] != expected_kind:
        raise OracleError(
            "SEMANTIC_EVENT_MISMATCH", f"{action_id} event kind is not source-owned"
        )
    if action_id in {"ipv4-only", "dual-stack"}:
        expected_mode = action_id
        if event["mode"] != expected_mode:
            raise OracleError("SEMANTIC_EVENT_MISMATCH", f"{action_id} mode is wrong")
    if action_id == "core-fault":
        _positive_int(event["corePid"], "event.corePid")
        exit_code = event["coreExitCode"]
        if (
            isinstance(exit_code, bool)
            or not isinstance(exit_code, int)
            or exit_code == 0
        ):
            raise OracleError(
                "SEMANTIC_EVENT_MISMATCH", "core fault must record a non-zero exit code"
            )
    if action_id == "wifi-lte-switch":
        transports = {event["fromTransport"], event["toTransport"]}
        if transports != {"CELLULAR", "WIFI"}:
            raise OracleError(
                "SEMANTIC_EVENT_MISMATCH",
                "network switch must cross Wi-Fi and cellular",
            )
        before = _digest(
            event["fromNetworkHandleSha256"], SHA256_RE, "event.fromNetworkHandleSha256"
        )
        after = _digest(
            event["toNetworkHandleSha256"], SHA256_RE, "event.toNetworkHandleSha256"
        )
        if before == after:
            raise OracleError("SEMANTIC_EVENT_MISMATCH", "network handles must change")
    if action_id == "sleep-wake":
        sleep_at = _positive_int(event["sleepAtEpochMs"], "event.sleepAtEpochMs")
        wake_at = _positive_int(event["wakeAtEpochMs"], "event.wakeAtEpochMs")
        if sleep_at >= wake_at:
            raise OracleError("SEMANTIC_EVENT_MISMATCH", "sleep must precede wake")
        return wake_at
    if action_id == "android-always-on-block" and (
        not isinstance(event["packageName"], str)
        or PACKAGE_RE.fullmatch(event["packageName"]) is None
    ):
        raise OracleError("SEMANTIC_EVENT_MISMATCH", "always-on package is invalid")
    return _positive_int(event["observedAtEpochMs"], "event.observedAtEpochMs")


def _validate_probe(
    value: Any,
    *,
    expected: tuple[str, str, str],
    fixture: dict[str, Any],
    window: tuple[int, int],
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise OracleError("SEMANTIC_SCHEMA_INVALID", "receipt probe must be an object")
    _exact_keys(
        value,
        {
            "error",
            "family",
            "finishedAtEpochMs",
            "id",
            "outcome",
            "startedAtEpochMs",
            "targetAddress",
            "targetPort",
        },
        "receipt probe",
    )
    expected_id, family, outcome = expected
    if (value["id"], value["family"], value["outcome"]) != expected:
        raise OracleError(
            "SEMANTIC_PROBE_MISMATCH",
            f"probe {expected_id} does not match its source-owned contract",
        )
    target_key = "controlIpv4" if family == "ipv4" else "controlIpv6"
    target_address = _ip(
        value["targetAddress"],
        f"probe {expected_id} targetAddress",
        version=4 if family == "ipv4" else 6,
    )
    if (
        str(target_address) != fixture[target_key]
        or value["targetPort"] != fixture["probePort"]
    ):
        raise OracleError(
            "SEMANTIC_PROBE_MISMATCH", f"probe {expected_id} is not fixture-bound"
        )
    started = _positive_int(value["startedAtEpochMs"], f"probe {expected_id} start")
    finished = _positive_int(value["finishedAtEpochMs"], f"probe {expected_id} finish")
    if not window[0] <= started < finished <= window[1]:
        raise OracleError(
            "SEMANTIC_WINDOW_MISMATCH",
            f"probe {expected_id} is outside the action window",
        )
    if outcome == "connected":
        if value["error"] is not None:
            raise OracleError(
                "SEMANTIC_PROBE_MISMATCH",
                f"connected probe {expected_id} carries an error",
            )
    elif value["error"] not in {"network-unreachable", "permission-denied", "timeout"}:
        raise OracleError(
            "SEMANTIC_PROBE_MISMATCH",
            f"blocked probe {expected_id} has no fail-closed error",
        )
    return value


def parse_receipt(context: ActionContext) -> tuple[dict[str, Any], dict[str, Any]]:
    receipt = _strict_json(
        context.artifacts["action-receipt"].payload, label="action receipt"
    )
    _reject_assertion_fields(receipt, label="action receipt")
    _exact_keys(
        receipt,
        {
            "actionId",
            "appApkSha256",
            "correlationId",
            "dnsObservation",
            "event",
            "fixture",
            "probes",
            "sourceSha",
            "testApkSha256",
            "version",
            "windowFinishedAtEpochMs",
            "windowStartedAtEpochMs",
        },
        "action receipt",
    )
    expected = {
        "version": RECEIPT_VERSION,
        "actionId": context.action_id,
        "correlationId": context.correlation_id,
        "sourceSha": context.source_sha,
        "appApkSha256": context.app_apk_sha256,
        "testApkSha256": context.test_apk_sha256,
        "windowStartedAtEpochMs": context.window_started_at_ms,
        "windowFinishedAtEpochMs": context.window_finished_at_ms,
    }
    for field, expected_value in expected.items():
        if receipt[field] != expected_value or type(receipt[field]) is not type(
            expected_value
        ):
            raise OracleError(
                "SEMANTIC_BINDING_MISMATCH", f"receipt.{field} is not manifest-bound"
            )
    fixture = _validate_fixture(receipt["fixture"], action_id=context.action_id)
    event = receipt["event"]
    if not isinstance(event, dict):
        raise OracleError("SEMANTIC_SCHEMA_INVALID", "receipt.event must be an object")
    event_at = _event_time(event, context.action_id)
    event_started_at = (
        event["sleepAtEpochMs"] if context.action_id == "sleep-wake" else event_at
    )
    if not (
        context.window_started_at_ms
        <= event_started_at
        <= event_at
        <= context.window_finished_at_ms
    ):
        raise OracleError(
            "SEMANTIC_WINDOW_MISMATCH", "event interval is outside the action window"
        )
    probes = receipt["probes"]
    expected_probes = EXPECTED_PROBES[context.action_id]
    if not isinstance(probes, list) or len(probes) != len(expected_probes):
        raise OracleError(
            "SEMANTIC_PROBE_MISMATCH", "receipt probes do not exactly cover the action"
        )
    validated_probes = [
        _validate_probe(
            probe,
            expected=expected_probe,
            fixture=fixture,
            window=(context.window_started_at_ms, context.window_finished_at_ms),
        )
        for probe, expected_probe in zip(probes, expected_probes, strict=True)
    ]
    dns = receipt["dnsObservation"]
    if context.action_id in {"ipv4-only", "dual-stack"}:
        if not isinstance(dns, dict):
            raise OracleError("SEMANTIC_DNS_MISMATCH", "DNS observation is missing")
        _exact_keys(
            dns,
            {
                "answers",
                "attemptCount",
                "finishedAtEpochMs",
                "queryNameSha256",
                "responseCode",
                "startedAtEpochMs",
            },
            "receipt.dnsObservation",
        )
        _digest(dns["queryNameSha256"], SHA256_RE, "dnsObservation.queryNameSha256")
        attempt_count = _positive_int(dns["attemptCount"], "DNS attempt count")
        if attempt_count > 3:
            raise OracleError(
                "SEMANTIC_DNS_MISMATCH", "DNS attempt count exceeds the producer limit"
            )
        dns_started = _positive_int(dns["startedAtEpochMs"], "DNS start")
        dns_finished = _positive_int(dns["finishedAtEpochMs"], "DNS finish")
        if (
            not context.window_started_at_ms
            <= dns_started
            < dns_finished
            <= context.window_finished_at_ms
        ):
            raise OracleError(
                "SEMANTIC_WINDOW_MISMATCH",
                "DNS observation is outside the action window",
            )
        answers = dns["answers"]
        if not isinstance(answers, list) or any(
            not isinstance(item, str) for item in answers
        ):
            raise OracleError(
                "SEMANTIC_DNS_MISMATCH", "DNS answers must be an array of IP literals"
            )
        parsed_answers = [
            str(_ip(item, f"dnsObservation.answers[{index}]"))
            for index, item in enumerate(answers)
        ]
        if context.action_id == "ipv4-only":
            if dns["responseCode"] not in {0, 2, 3, 5} or parsed_answers:
                raise OracleError(
                    "SEMANTIC_IPV4_ONLY_DNS_LEAK",
                    "IPv4-only AAAA response was not empty or blocked",
                )
        elif dns["responseCode"] != 0 or parsed_answers != [
            fixture["controlIpv6"]
        ]:
            raise OracleError(
                "SEMANTIC_DUAL_STACK_DNS_INVALID",
                "dual-stack AAAA response does not bind the IPv6 fixture",
            )
    elif dns is not None:
        raise OracleError(
            "SEMANTIC_SCHEMA_INVALID", "non-DNS action must not carry a DNS observation"
        )
    return receipt, {
        "fixture": fixture,
        "eventAt": event_at,
        "eventStartedAt": event_started_at,
        "probes": validated_probes,
    }


def parse_route_snapshot(
    context: ActionContext, *, receipt: dict[str, Any]
) -> list[dict[str, Any]]:
    document = _strict_json(
        context.artifacts["route-snapshot"].payload, label="route snapshot"
    )
    _reject_assertion_fields(document, label="route snapshot")
    _exact_keys(
        document,
        {
            "actionId",
            "correlationId",
            "phases",
            "sourceSha",
            "version",
            "windowFinishedAtEpochMs",
            "windowStartedAtEpochMs",
        },
        "route snapshot",
    )
    expected = {
        "version": ROUTE_SNAPSHOT_VERSION,
        "actionId": context.action_id,
        "correlationId": context.correlation_id,
        "sourceSha": context.source_sha,
        "windowStartedAtEpochMs": context.window_started_at_ms,
        "windowFinishedAtEpochMs": context.window_finished_at_ms,
    }
    for field, expected_value in expected.items():
        if document[field] != expected_value or type(document[field]) is not type(
            expected_value
        ):
            raise OracleError(
                "SEMANTIC_BINDING_MISMATCH",
                f"route snapshot {field} is not manifest-bound",
            )
    phases = document["phases"]
    expected_names = EXPECTED_PHASES[context.action_id]
    if not isinstance(phases, list) or len(phases) != len(expected_names):
        raise OracleError(
            "SEMANTIC_ROUTE_MISMATCH", "route phases do not exactly cover the action"
        )
    parsed: list[dict[str, Any]] = []
    previous_at = 0
    for phase, expected_name in zip(phases, expected_names, strict=True):
        if not isinstance(phase, dict):
            raise OracleError(
                "SEMANTIC_SCHEMA_INVALID", "route phase must be an object"
            )
        _exact_keys(
            phase,
            {"capturedAtEpochMs", "commands", "name", "vpnInterface"},
            "route phase",
        )
        if phase["name"] != expected_name:
            raise OracleError(
                "SEMANTIC_ROUTE_MISMATCH", "route phase order is source-owned"
            )
        captured_at = _positive_int(
            phase["capturedAtEpochMs"], f"route phase {expected_name} time"
        )
        if (
            not context.window_started_at_ms
            <= captured_at
            <= context.window_finished_at_ms
            or captured_at <= previous_at
        ):
            raise OracleError("SEMANTIC_WINDOW_MISMATCH", "route phase time is invalid")
        previous_at = captured_at
        interface = phase["vpnInterface"]
        if not isinstance(interface, str) or INTERFACE_RE.fullmatch(interface) is None:
            raise OracleError("SEMANTIC_ROUTE_MISMATCH", "VPN interface is malformed")
        commands = phase["commands"]
        if not isinstance(commands, dict):
            raise OracleError(
                "SEMANTIC_SCHEMA_INVALID", "route commands must be an object"
            )
        _exact_keys(
            commands,
            {
                "connectivity",
                "dnsServers",
                "ip6AddressShow",
                "ip6RouteShow",
                "ipAddressShow",
                "ipRouteShow",
                "secureSettings",
            },
            "route commands",
        )
        for name, output in commands.items():
            if (
                not isinstance(output, str)
                or "\x00" in output
                or len(output.encode()) > 64 * 1024
            ):
                raise OracleError(
                    "SEMANTIC_ROUTE_MISMATCH", f"route command {name} is invalid"
                )
        facts = _route_facts(interface, commands)
        parsed.append({"name": expected_name, "capturedAt": captured_at, **facts})
    _validate_route_facts(context.action_id, parsed, receipt=receipt)
    return parsed


def _key_values(raw: str, label: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        if key in result:
            raise OracleError("SEMANTIC_ROUTE_MISMATCH", f"{label} duplicates {key}")
        result[key] = value
    return result


def _default_route(raw: str, interface: str, *, ipv6: bool) -> bool:
    prefixes = {"default", "::/0"} if ipv6 else {"default", "0.0.0.0/0"}
    for line in raw.splitlines():
        fields = line.strip().split()
        if not fields or fields[0] not in prefixes:
            continue
        if any(
            fields[index : index + 2] == ["dev", interface]
            for index in range(len(fields) - 1)
        ):
            return True
    return False


def _global_ipv6_addresses(raw: str) -> tuple[str, ...]:
    result: list[str] = []
    for match in re.finditer(r"(?:^|\s)inet6\s+([^/\s]+)/\d+", raw, flags=re.MULTILINE):
        try:
            address = ipaddress.ip_address(match.group(1).split("%", 1)[0])
        except ValueError:
            continue
        if address.version == 6 and not (
            address.is_unspecified
            or address.is_loopback
            or address.is_link_local
            or address.is_multicast
        ):
            result.append(str(address))
    return tuple(sorted(set(result)))


def _interface_block(raw: str, interface: str) -> str | None:
    blocks: list[str] = []
    block: list[str] = []
    current_name: str | None = None
    for line in raw.splitlines(keepends=True):
        header = re.match(r"^\d+:\s+([^:]+):", line)
        if header is not None:
            if current_name == interface and block:
                blocks.append("".join(block))
            block = []
            name = header.group(1).split("@", 1)[0]
            current_name = name
        if current_name == interface:
            block.append(line)
    if current_name == interface and block:
        blocks.append("".join(block))
    if len(blocks) > 1:
        raise OracleError(
            "SEMANTIC_ROUTE_MISMATCH",
            "address command output duplicates the declared VPN interface",
        )
    return blocks[0] if blocks else None


def _interface_is_up(header: str) -> bool:
    flags_match = re.search(r"<([^>]*)>", header)
    flags = set(flags_match.group(1).split(",")) if flags_match is not None else set()
    state_match = re.search(r"\bstate\s+(\S+)", header)
    state = state_match.group(1) if state_match is not None else None
    return "UP" in flags and state not in {"DOWN", "DORMANT", "LOWERLAYERDOWN"}


def _global_ipv4_addresses(raw: str) -> tuple[str, ...]:
    result: list[str] = []
    for match in re.finditer(r"(?:^|\s)inet\s+([^/\s]+)/\d+", raw, flags=re.MULTILINE):
        try:
            address = ipaddress.ip_address(match.group(1))
        except ValueError:
            continue
        if address.version == 4 and not (
            address.is_unspecified
            or address.is_loopback
            or address.is_link_local
            or address.is_multicast
        ):
            result.append(str(address))
    return tuple(sorted(set(result)))


def _dns_addresses(raw: str) -> tuple[str, ...]:
    result: list[str] = []
    for token in re.split(r"[\s,;\[\]()]+", raw):
        candidate = token.strip().rstrip(".")
        if not candidate:
            continue
        if candidate.count(":") == 1 and "." in candidate:
            candidate = candidate.rsplit(":", 1)[0]
        try:
            result.append(str(ipaddress.ip_address(candidate.split("%", 1)[0])))
        except ValueError:
            continue
    return tuple(sorted(set(result)))


def _route_facts(interface: str, commands: dict[str, str]) -> dict[str, Any]:
    connectivity = _key_values(commands["connectivity"], "connectivity")
    settings = _key_values(commands["secureSettings"], "secure settings")
    if connectivity.get("vpn_active") not in {"true", "false"} or connectivity.get(
        "lockdown_active"
    ) not in {"true", "false"}:
        raise OracleError(
            "SEMANTIC_ROUTE_MISMATCH",
            "connectivity must expose exact VPN and lockdown states",
        )
    combined_block = _interface_block(commands["ipAddressShow"], interface)
    ipv6_block = _interface_block(commands["ip6AddressShow"], interface)
    if combined_block is None or ipv6_block is None:
        raise OracleError(
            "SEMANTIC_ROUTE_MISMATCH",
            "address command outputs do not identify the declared VPN interface",
        )
    combined_header = combined_block.splitlines()[0]
    ipv6_header = ipv6_block.splitlines()[0]
    vpn_active = connectivity["vpn_active"] == "true"
    interface_up = _interface_is_up(combined_header) and _interface_is_up(ipv6_header)
    global_ipv4 = _global_ipv4_addresses(combined_block)
    if vpn_active and (not interface_up or not global_ipv4):
        raise OracleError(
            "SEMANTIC_ROUTE_MISMATCH",
            "active VPN interface must be UP and expose an IPv4 address",
        )
    combined_global_ipv6 = _global_ipv6_addresses(combined_block)
    ipv6_global_ipv6 = _global_ipv6_addresses(ipv6_block)
    if combined_global_ipv6 != ipv6_global_ipv6:
        raise OracleError(
            "SEMANTIC_ROUTE_MISMATCH",
            "combined and IPv6-specific address outputs contradict each other",
        )
    return {
        "interface": interface,
        "interfaceUp": interface_up,
        "globalIpv4": global_ipv4,
        "vpnActive": vpn_active,
        "lockdownActive": connectivity["lockdown_active"] == "true",
        "ipv4Default": _default_route(commands["ipRouteShow"], interface, ipv6=False),
        "ipv6Default": _default_route(commands["ip6RouteShow"], interface, ipv6=True),
        "globalIpv6": ipv6_global_ipv6,
        "dnsAddresses": _dns_addresses(commands["dnsServers"]),
        "settings": settings,
    }


def _validate_route_facts(
    action_id: str, phases: list[dict[str, Any]], *, receipt: dict[str, Any]
) -> None:
    if action_id == "ipv4-only":
        steady = phases[0]
        if not steady["vpnActive"] or not steady["ipv4Default"]:
            raise OracleError(
                "SEMANTIC_IPV4_ONLY_ROUTE_LEAK", "IPv4-only VPN route is not active"
            )
        if (
            steady["globalIpv6"]
            or steady["ipv6Default"]
            or any(":" in item for item in steady["dnsAddresses"])
        ):
            raise OracleError(
                "SEMANTIC_IPV4_ONLY_ROUTE_LEAK",
                "IPv4-only snapshot exposes IPv6 address, route, or DNS",
            )
        return
    if action_id == "dual-stack":
        steady = phases[0]
        if (
            not steady["vpnActive"]
            or not steady["ipv4Default"]
            or not steady["ipv6Default"]
            or not steady["globalIpv6"]
        ):
            raise OracleError(
                "SEMANTIC_DUAL_STACK_ROUTE_INVALID",
                "dual-stack tunnel lacks its IPv4/IPv6 routes or address",
            )
        return
    if action_id in {"core-fault", "forced-revoke"}:
        closed = phases[0]
        if (
            closed["vpnActive"]
            or not closed["lockdownActive"]
            or closed["ipv4Default"]
            or closed["ipv6Default"]
        ):
            raise OracleError(
                "SEMANTIC_KILLSWITCH_ROUTE_OPEN",
                f"{action_id} route snapshot is not fail-closed",
            )
        if action_id == "forced-revoke" and receipt["event"]["appOpMode"] != "ignore":
            raise OracleError(
                "SEMANTIC_PERMISSION_REVOKE_INVALID",
                "permission revoke did not enter ignore mode",
            )
        return
    protected = phases[0]
    if (
        not protected["vpnActive"]
        or not protected["lockdownActive"]
        or not protected["ipv4Default"]
    ):
        raise OracleError(
            "SEMANTIC_KILLSWITCH_ROUTE_OPEN",
            f"{action_id} did not remain protected by the VPN",
        )
    if action_id == "android-always-on-block":
        settings = protected["settings"]
        if (
            settings.get("always_on_vpn_app") != receipt["event"]["packageName"]
            or settings.get("always_on_vpn_lockdown") != "1"
        ):
            raise OracleError(
                "SEMANTIC_ALWAYS_ON_INVALID",
                "Android always-on lockdown settings are not active",
            )


def parse_classic_pcap(raw: bytes) -> list[Packet]:
    if len(raw) < 24 or len(raw) > MAX_PCAP_BYTES:
        raise OracleError("SEMANTIC_PCAP_INVALID", "PCAP size is outside its bound")
    magic = raw[:4]
    formats = {
        b"\xd4\xc3\xb2\xa1": ("<", 1_000),
        b"\xa1\xb2\xc3\xd4": (">", 1_000),
        b"\x4d\x3c\xb2\xa1": ("<", 1_000_000),
        b"\xa1\xb2\x3c\x4d": (">", 1_000_000),
    }
    try:
        endian, fraction_divisor = formats[magic]
    except KeyError as error:
        raise OracleError(
            "SEMANTIC_PCAP_INVALID", "only classic PCAP is accepted"
        ) from error
    _, major, minor, _, _, snaplen, link_type = struct.unpack(
        f"{endian}IHHIIII", raw[:24]
    )
    if (
        (major, minor) != (2, 4)
        or not 64 <= snaplen <= 1024 * 1024
        or link_type not in {1, 101, 113, 276}
    ):
        raise OracleError("SEMANTIC_PCAP_INVALID", "PCAP header is unsupported")
    packets: list[Packet] = []
    offset = 24
    while offset < len(raw):
        if len(raw) - offset < 16 or len(packets) >= MAX_PCAP_PACKETS:
            raise OracleError(
                "SEMANTIC_PCAP_INVALID", "PCAP record inventory is invalid"
            )
        seconds, fraction, included, original = struct.unpack(
            f"{endian}IIII", raw[offset : offset + 16]
        )
        offset += 16
        if (
            included <= 0
            or included > snaplen
            or included != original
            or offset + included > len(raw)
        ):
            raise OracleError(
                "SEMANTIC_PCAP_TRUNCATED", "PCAP contains a truncated record"
            )
        frame = raw[offset : offset + included]
        offset += included
        timestamp_ms = seconds * 1000 + fraction // fraction_divisor
        packet = _parse_frame(frame, link_type=link_type, timestamp_ms=timestamp_ms)
        if packet is not None:
            packets.append(packet)
    if not packets:
        raise OracleError("SEMANTIC_PCAP_INVALID", "PCAP contains no IP packets")
    return packets


def _parse_frame(frame: bytes, *, link_type: int, timestamp_ms: int) -> Packet | None:
    if link_type == 1:
        if len(frame) < 14:
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "Ethernet frame is truncated")
        protocol = int.from_bytes(frame[12:14], "big")
        offset = 14
        for _ in range(2):
            if protocol not in {0x8100, 0x88A8}:
                break
            if len(frame) < offset + 4:
                raise OracleError("SEMANTIC_PCAP_TRUNCATED", "VLAN header is truncated")
            protocol = int.from_bytes(frame[offset + 2 : offset + 4], "big")
            offset += 4
    elif link_type == 101:
        if not frame:
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "raw IP frame is empty")
        protocol = 0x0800 if frame[0] >> 4 == 4 else 0x86DD if frame[0] >> 4 == 6 else 0
        offset = 0
    elif link_type == 113:
        if len(frame) < 16:
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "SLL frame is truncated")
        protocol = int.from_bytes(frame[14:16], "big")
        offset = 16
    else:
        if len(frame) < 20:
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "SLL2 frame is truncated")
        protocol = int.from_bytes(frame[0:2], "big")
        offset = 20
    network = frame[offset:]
    if protocol == 0x0800:
        return _parse_ipv4(network, timestamp_ms)
    if protocol == 0x86DD:
        return _parse_ipv6(network, timestamp_ms)
    return None


def _ports_payload(
    payload: bytes, protocol: int
) -> tuple[int | None, int | None, bytes]:
    if protocol == 17:
        if len(payload) < 8:
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "UDP header is truncated")
        length = int.from_bytes(payload[4:6], "big")
        if length < 8 or length > len(payload):
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "UDP payload is truncated")
        return (
            int.from_bytes(payload[:2], "big"),
            int.from_bytes(payload[2:4], "big"),
            payload[8:length],
        )
    if protocol == 6:
        if len(payload) < 20:
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "TCP header is truncated")
        header = (payload[12] >> 4) * 4
        if header < 20 or header > len(payload):
            raise OracleError("SEMANTIC_PCAP_TRUNCATED", "TCP data offset is invalid")
        return (
            int.from_bytes(payload[:2], "big"),
            int.from_bytes(payload[2:4], "big"),
            payload[header:],
        )
    return None, None, b""


def _parse_ipv4(raw: bytes, timestamp_ms: int) -> Packet:
    if len(raw) < 20 or raw[0] >> 4 != 4:
        raise OracleError("SEMANTIC_PCAP_TRUNCATED", "IPv4 header is truncated")
    header = (raw[0] & 0x0F) * 4
    total = int.from_bytes(raw[2:4], "big")
    if header < 20 or total < header or total > len(raw):
        raise OracleError("SEMANTIC_PCAP_TRUNCATED", "IPv4 packet length is invalid")
    protocol = raw[9]
    source = str(ipaddress.ip_address(raw[12:16]))
    destination = str(ipaddress.ip_address(raw[16:20]))
    fragment = int.from_bytes(raw[6:8], "big")
    if fragment & 0x1FFF:
        source_port, destination_port, payload = None, None, b""
    else:
        source_port, destination_port, payload = _ports_payload(
            raw[header:total], protocol
        )
    return Packet(
        timestamp_ms,
        4,
        source,
        destination,
        protocol,
        source_port,
        destination_port,
        payload,
    )


def _parse_ipv6(raw: bytes, timestamp_ms: int) -> Packet:
    if len(raw) < 40 or raw[0] >> 4 != 6:
        raise OracleError("SEMANTIC_PCAP_TRUNCATED", "IPv6 header is truncated")
    total = 40 + int.from_bytes(raw[4:6], "big")
    if total > len(raw):
        raise OracleError("SEMANTIC_PCAP_TRUNCATED", "IPv6 packet length is invalid")
    next_header = raw[6]
    offset = 40
    for _ in range(8):
        if next_header in {0, 43, 60}:
            if offset + 2 > total:
                raise OracleError(
                    "SEMANTIC_PCAP_TRUNCATED", "IPv6 extension is truncated"
                )
            extension_length = (raw[offset + 1] + 1) * 8
            if offset + extension_length > total:
                raise OracleError(
                    "SEMANTIC_PCAP_TRUNCATED", "IPv6 extension is truncated"
                )
            next_header, offset = raw[offset], offset + extension_length
            continue
        if next_header == 44:
            if offset + 8 > total:
                raise OracleError(
                    "SEMANTIC_PCAP_TRUNCATED", "IPv6 fragment is truncated"
                )
            fragment_offset = int.from_bytes(raw[offset + 2 : offset + 4], "big") >> 3
            next_header, offset = raw[offset], offset + 8
            if fragment_offset:
                source_port, destination_port, payload = None, None, b""
                break
            continue
        source_port, destination_port, payload = _ports_payload(
            raw[offset:total], next_header
        )
        break
    else:
        raise OracleError("SEMANTIC_PCAP_INVALID", "IPv6 extension chain is too deep")
    return Packet(
        timestamp_ms,
        6,
        str(ipaddress.ip_address(raw[8:24])),
        str(ipaddress.ip_address(raw[24:40])),
        next_header,
        source_port,
        destination_port,
        payload,
    )


def _marker(action_id: str, correlation_id: str, phase: str) -> bytes:
    return f"{MARKER_PREFIX}:{action_id}:{correlation_id}:{phase}".encode("ascii")


def _packets_and_markers(
    context: ActionContext,
    fixture: dict[str, Any],
) -> tuple[list[Packet], dict[str, list[Packet]]]:
    packets = parse_classic_pcap(context.artifacts["packet-capture"].payload)
    marker_packets: dict[str, list[Packet]] = {"action": [], "outcome": []}
    for packet in packets:
        if (
            packet.protocol == 17
            and packet.destination == fixture["markerAddress"]
            and packet.destination_port == fixture["markerPort"]
        ):
            for phase in marker_packets:
                if packet.payload == _marker(
                    context.action_id, context.correlation_id, phase
                ):
                    marker_packets[phase].append(packet)
    if any(len(values) != 1 for values in marker_packets.values()):
        raise OracleError(
            "SEMANTIC_MARKER_MISMATCH",
            "PCAP must contain one exact action and outcome marker",
        )
    return packets, marker_packets


def resolve_capture_clock_offset(contexts: list[ActionContext]) -> int:
    if not contexts:
        raise OracleError(
            "SEMANTIC_CLOCK_MISMATCH", "capture clock requires at least one action"
        )
    lower_bound = -MAX_CAPTURE_CLOCK_OFFSET_MS
    upper_bound = MAX_CAPTURE_CLOCK_OFFSET_MS
    for context in contexts:
        receipt, receipt_facts = parse_receipt(context)
        route_facts = parse_route_snapshot(context, receipt=receipt)
        _, marker_packets = _packets_and_markers(
            context, receipt_facts["fixture"]
        )
        action_at = marker_packets["action"][0].timestamp_ms
        outcome_at = marker_packets["outcome"][0].timestamp_ms
        if action_at >= outcome_at:
            raise OracleError(
                "SEMANTIC_WINDOW_MISMATCH", "PCAP marker order is invalid"
            )
        lower_bound = max(
            lower_bound,
            outcome_at - context.window_finished_at_ms,
            action_at - receipt_facts["eventAt"],
        )
        observations_finished = [
            *(probe["finishedAtEpochMs"] for probe in receipt_facts["probes"]),
            *(phase["capturedAt"] for phase in route_facts),
        ]
        dns = receipt["dnsObservation"]
        if isinstance(dns, dict):
            observations_finished.append(dns["finishedAtEpochMs"])
        upper_bound = min(
            upper_bound,
            action_at - context.window_started_at_ms,
            outcome_at - max(observations_finished),
        )
        if context.action_id == "sleep-wake":
            upper_bound = min(
                upper_bound, action_at - receipt_facts["eventStartedAt"]
            )
    if lower_bound > upper_bound:
        raise OracleError(
            "SEMANTIC_CLOCK_MISMATCH",
            "PCAP and Android windows do not share one bounded clock offset",
        )
    if lower_bound <= 0 <= upper_bound:
        return 0
    return lower_bound if lower_bound > 0 else upper_bound


def evaluate_pcap(
    context: ActionContext,
    *,
    fixture: dict[str, Any],
    event_at: int,
    capture_clock_offset_ms: int,
) -> dict[str, Any]:
    packets, marker_packets = _packets_and_markers(context, fixture)
    action_at = marker_packets["action"][0].timestamp_ms - capture_clock_offset_ms
    outcome_at = (
        marker_packets["outcome"][0].timestamp_ms - capture_clock_offset_ms
    )
    if (
        not context.window_started_at_ms
        <= action_at
        < outcome_at
        <= context.window_finished_at_ms
    ):
        raise OracleError("SEMANTIC_WINDOW_MISMATCH", "PCAP marker window is invalid")
    scoped = [
        packet
        for packet in packets
        if context.window_started_at_ms
        <= packet.timestamp_ms - capture_clock_offset_ms
        <= context.window_finished_at_ms
    ]
    direct = [
        packet
        for packet in scoped
        if (
            packet.source in {fixture["controlIpv4"], fixture["controlIpv6"]}
            and packet.source_port == fixture["probePort"]
            and packet.destination
            not in {fixture["controlIpv4"], fixture["controlIpv6"]}
        )
        or (
            packet.destination in {fixture["controlIpv4"], fixture["controlIpv6"]}
            and packet.destination_port == fixture["probePort"]
            and packet.source not in {fixture["controlIpv4"], fixture["controlIpv6"]}
        )
    ]
    if direct:
        raise OracleError(
            "SEMANTIC_DIRECT_TRAFFIC_LEAK",
            f"{context.action_id} exposed fixture traffic on the underlay",
        )
    if context.action_id == "ipv4-only" and any(
        packet.ip_version == 6 for packet in scoped
    ):
        raise OracleError(
            "SEMANTIC_IPV4_ONLY_PACKET_LEAK",
            "IPv4-only underlay window contains IPv6 traffic",
        )
    fixture_local = [
        packet
        for packet in scoped
        if packet.source == packet.destination
        and packet.source in {fixture["controlIpv4"], fixture["controlIpv6"]}
    ]
    tunnel_packets = [
        packet
        for packet in scoped
        if (
            packet.source in fixture["tunnelEndpoints"]
            and packet.source_port == fixture["tunnelPort"]
        )
        or (
            packet.destination in fixture["tunnelEndpoints"]
            and packet.destination_port == fixture["tunnelPort"]
        )
    ]
    outcome_tunnel_packets = [
        packet
        for packet in tunnel_packets
        if event_at
        <= packet.timestamp_ms - capture_clock_offset_ms
        <= outcome_at
    ]
    if context.action_id in TUNNEL_ACTIVITY_ACTIONS and not outcome_tunnel_packets:
        raise OracleError(
            "SEMANTIC_TUNNEL_CONTROL_MISSING",
            f"{context.action_id} lacks causally ordered packet-derived tunnel activity",
        )
    marker_values = marker_packets["action"] + marker_packets["outcome"]
    unexpected = [
        packet
        for packet in scoped
        if packet not in marker_values
        and packet not in fixture_local
        and packet not in tunnel_packets
    ]
    if unexpected:
        raise OracleError(
            "SEMANTIC_UNEXPECTED_UNDERLAY_TRAFFIC",
            f"{context.action_id} contains traffic outside marker and approved tunnel endpoints",
        )
    return {
        "actionMarkerAtEpochMs": action_at,
        "captureClockOffsetMs": capture_clock_offset_ms,
        "outcomeMarkerAtEpochMs": outcome_at,
        "parsedPacketCount": len(packets),
        "tunnelPacketCount": len(outcome_tunnel_packets),
        "tunnelPacketTimesEpochMs": [
            packet.timestamp_ms - capture_clock_offset_ms
            for packet in outcome_tunnel_packets
        ],
        "windowPacketCount": len(scoped),
    }


def _validate_causal_order(
    context: ActionContext,
    *,
    receipt: dict[str, Any],
    receipt_facts: dict[str, Any],
    route_facts: list[dict[str, Any]],
    pcap_facts: dict[str, Any],
) -> None:
    action_at = pcap_facts["actionMarkerAtEpochMs"]
    outcome_at = pcap_facts["outcomeMarkerAtEpochMs"]
    event_at = receipt_facts["eventAt"]
    if context.action_id == "sleep-wake":
        if not receipt_facts["eventStartedAt"] <= action_at <= event_at:
            raise OracleError(
                "SEMANTIC_CAUSAL_ORDER_INVALID",
                "sleep/action/wake evidence is not causally ordered",
            )
    elif action_at > event_at:
        raise OracleError(
            "SEMANTIC_CAUSAL_ORDER_INVALID",
            "action marker must not follow the source-owned event",
        )

    probes = receipt_facts["probes"]
    previous_finished = event_at
    for probe in probes:
        if probe["startedAtEpochMs"] < previous_finished:
            raise OracleError(
                "SEMANTIC_CAUSAL_ORDER_INVALID",
                "probe observations overlap or precede their source-owned event",
            )
        previous_finished = probe["finishedAtEpochMs"]

    observations_started = [
        *(probe["startedAtEpochMs"] for probe in probes),
        *(phase["capturedAt"] for phase in route_facts),
    ]
    observations_finished = [
        *(probe["finishedAtEpochMs"] for probe in probes),
        *(phase["capturedAt"] for phase in route_facts),
    ]
    dns = receipt["dnsObservation"]
    if isinstance(dns, dict):
        observations_started.append(dns["startedAtEpochMs"])
        observations_finished.append(dns["finishedAtEpochMs"])
    if any(started < event_at for started in observations_started) or any(
        finished > outcome_at for finished in observations_finished
    ):
        raise OracleError(
            "SEMANTIC_CAUSAL_ORDER_INVALID",
            "observations must follow the event and finish before the outcome marker",
        )


def evaluate_action(
    context: ActionContext,
    *,
    capture_clock_offset_ms: int | None = None,
) -> dict[str, Any]:
    if context.action_id not in EXPECTED_PROBES:
        raise OracleError(
            "SEMANTIC_ACTION_UNSUPPORTED", f"unsupported action {context.action_id}"
        )
    if set(context.artifacts) != {"action-receipt", "packet-capture", "route-snapshot"}:
        raise OracleError(
            "SEMANTIC_INVENTORY_MISMATCH", "semantic artifacts are incomplete"
        )
    _digest(context.source_sha, SHA1_RE, "sourceSha")
    _digest(context.correlation_id, SHA256_RE, "correlationId")
    _digest(context.app_apk_sha256, SHA256_RE, "appApkSha256")
    _digest(context.test_apk_sha256, SHA256_RE, "testApkSha256")
    receipt, receipt_facts = parse_receipt(context)
    route_facts = parse_route_snapshot(context, receipt=receipt)
    if capture_clock_offset_ms is None:
        capture_clock_offset_ms = resolve_capture_clock_offset([context])
    pcap_facts = evaluate_pcap(
        context,
        fixture=receipt_facts["fixture"],
        event_at=receipt_facts["eventAt"],
        capture_clock_offset_ms=capture_clock_offset_ms,
    )
    _validate_causal_order(
        context,
        receipt=receipt,
        receipt_facts=receipt_facts,
        route_facts=route_facts,
        pcap_facts=pcap_facts,
    )
    facts = {
        "actionId": context.action_id,
        "eventAtEpochMs": receipt_facts["eventAt"],
        "gateIds": list(context.gate_ids),
        "pcap": pcap_facts,
        "routePhases": [
            {
                "capturedAtEpochMs": phase["capturedAt"],
                "globalIpv4": list(phase["globalIpv4"]),
                "globalIpv6": list(phase["globalIpv6"]),
                "interfaceUp": phase["interfaceUp"],
                "ipv4Default": phase["ipv4Default"],
                "ipv6Default": phase["ipv6Default"],
                "lockdownActive": phase["lockdownActive"],
                "name": phase["name"],
                "vpnActive": phase["vpnActive"],
            }
            for phase in route_facts
        ],
    }
    return {
        "artifacts": {
            kind: context.artifacts[kind].sha256
            for kind in ("action-receipt", "packet-capture", "route-snapshot")
        },
        "factsSha256": hashlib.sha256(canonical_json_bytes(facts)).hexdigest(),
        "gateIds": list(context.gate_ids),
    }
