"""Deterministic source-owned fixtures for ordinary semantic oracle tests."""

from __future__ import annotations

import hashlib
import ipaddress
import struct
from typing import Any

from scripts.ci import android_ordinary_semantic_oracles as oracles


FIXTURE = {
    "controlIpv4": "192.0.2.10",
    "controlIpv6": "2001:db8::10",
    "markerAddress": "192.0.2.200",
    "markerPort": 39001,
    "probePort": 443,
    "tunnelEndpoints": ["198.51.100.20"],
    "tunnelPort": 8443,
}


def digest(label: str) -> str:
    return hashlib.sha256(label.encode("ascii")).hexdigest()


def _probe(
    probe_id: str,
    family: str,
    outcome: str,
    *,
    started_at: int,
) -> dict[str, Any]:
    return {
        "error": None if outcome == "connected" else "network-unreachable",
        "family": family,
        "finishedAtEpochMs": started_at + 25,
        "id": probe_id,
        "outcome": outcome,
        "startedAtEpochMs": started_at,
        "targetAddress": FIXTURE["controlIpv4" if family == "ipv4" else "controlIpv6"],
        "targetPort": FIXTURE["probePort"],
    }


def _event(action_id: str, *, started_at: int) -> dict[str, Any]:
    observed_at = started_at + 120
    if action_id in {"ipv4-only", "dual-stack"}:
        return {
            "kind": "mode-applied",
            "mode": action_id,
            "observedAtEpochMs": observed_at,
        }
    if action_id == "forced-revoke":
        return {
            "appOpMode": "ignore",
            "kind": "vpn-permission-revoke-failed-closed-under-lockdown",
            "observedAtEpochMs": observed_at,
        }
    if action_id == "core-fault":
        return {
            "coreExitCode": 19,
            "corePid": 4101,
            "kind": "native-core-exited",
            "observedAtEpochMs": observed_at,
        }
    if action_id == "wifi-lte-switch":
        return {
            "fromNetworkHandleSha256": digest(f"{action_id}:wifi"),
            "fromTransport": "WIFI",
            "kind": "underlay-changed",
            "observedAtEpochMs": observed_at,
            "toNetworkHandleSha256": digest(f"{action_id}:cellular"),
            "toTransport": "CELLULAR",
        }
    if action_id == "sleep-wake":
        return {
            "kind": "device-wake",
            "sleepAtEpochMs": started_at + 80,
            "wakeAtEpochMs": observed_at,
        }
    if action_id == "android-always-on-block":
        return {
            "kind": "vpn-stop-absorbed-under-lockdown",
            "observedAtEpochMs": observed_at,
            "packageName": "com.poyka.ripdpi",
        }
    raise AssertionError(action_id)


def action_receipt(
    action_id: str,
    *,
    correlation_id: str,
    source_sha: str,
    app_sha256: str,
    test_sha256: str,
    started_at: int,
    finished_at: int,
) -> bytes:
    probe_offsets = tuple(
        200 + index * 80 for index in range(len(oracles.EXPECTED_PROBES[action_id]))
    )
    probes = [
        _probe(
            probe_id,
            family,
            outcome,
            started_at=started_at + offset,
        )
        for offset, (probe_id, family, outcome) in zip(
            probe_offsets, oracles.EXPECTED_PROBES[action_id], strict=True
        )
    ]
    dns: dict[str, Any] | None = None
    if action_id == "ipv4-only":
        dns = {
            "answers": [],
            "attemptCount": 1,
            "finishedAtEpochMs": started_at + 575,
            "queryNameSha256": digest(f"{action_id}:query"),
            "responseCode": 0,
            "startedAtEpochMs": started_at + 525,
        }
    elif action_id == "dual-stack":
        dns = {
            "answers": [FIXTURE["controlIpv6"]],
            "attemptCount": 1,
            "finishedAtEpochMs": started_at + 575,
            "queryNameSha256": digest(f"{action_id}:query"),
            "responseCode": 0,
            "startedAtEpochMs": started_at + 525,
        }
    return oracles.canonical_json_bytes(
        {
            "actionId": action_id,
            "appApkSha256": app_sha256,
            "correlationId": correlation_id,
            "dnsObservation": dns,
            "event": _event(action_id, started_at=started_at),
            "fixture": FIXTURE,
            "probes": probes,
            "sourceSha": source_sha,
            "testApkSha256": test_sha256,
            "version": oracles.RECEIPT_VERSION,
            "windowFinishedAtEpochMs": finished_at,
            "windowStartedAtEpochMs": started_at,
        }
    )


def _commands(
    *,
    vpn_active: bool,
    lockdown_active: bool,
    ipv4_default: bool,
    ipv6_default: bool,
    global_ipv6: bool,
    secure_settings: str = "",
) -> dict[str, str]:
    flags = "POINTOPOINT,UP" if vpn_active else "POINTOPOINT,DOWN"
    combined_addresses = f"7: tun0: <{flags}> mtu 1500\n"
    if vpn_active:
        combined_addresses += "    inet 10.0.0.2/32 scope global tun0\n"
    if global_ipv6:
        combined_addresses += "    inet6 fd00:1234::2/128 scope global\n"
    return {
        "connectivity": (
            f"vpn_active={'true' if vpn_active else 'false'}\n"
            f"lockdown_active={'true' if lockdown_active else 'false'}\n"
        ),
        "dnsServers": "nameserver 10.0.0.53\n",
        "ip6AddressShow": (
            f"7: tun0: <{flags}> mtu 1500\n    inet6 fd00:1234::2/128 scope global\n"
            if global_ipv6
            else f"7: tun0: <{flags}> mtu 1500\n"
        ),
        "ip6RouteShow": "default dev tun0 metric 1024\n" if ipv6_default else "",
        "ipAddressShow": combined_addresses,
        "ipRouteShow": "default dev tun0 scope link\n" if ipv4_default else "",
        "secureSettings": secure_settings,
    }


def route_snapshot(
    action_id: str,
    *,
    correlation_id: str,
    source_sha: str,
    started_at: int,
    finished_at: int,
) -> bytes:
    phases: list[dict[str, Any]]
    if action_id == "ipv4-only":
        phases = [
            {
                "capturedAtEpochMs": started_at + 700,
                "commands": _commands(
                    vpn_active=True,
                    lockdown_active=True,
                    ipv4_default=True,
                    ipv6_default=False,
                    global_ipv6=False,
                ),
                "name": "steady",
                "vpnInterface": "tun0",
            }
        ]
    elif action_id == "dual-stack":
        phases = [
            {
                "capturedAtEpochMs": started_at + 700,
                "commands": _commands(
                    vpn_active=True,
                    lockdown_active=True,
                    ipv4_default=True,
                    ipv6_default=True,
                    global_ipv6=True,
                ),
                "name": "steady",
                "vpnInterface": "tun0",
            }
        ]
    elif action_id in {"wifi-lte-switch", "sleep-wake", "android-always-on-block"}:
        secure_settings = (
            "always_on_vpn_app=com.poyka.ripdpi\nalways_on_vpn_lockdown=1\n"
            if action_id == "android-always-on-block"
            else ""
        )
        phases = [
            {
                "capturedAtEpochMs": started_at + 400,
                "commands": _commands(
                    vpn_active=True,
                    lockdown_active=True,
                    ipv4_default=True,
                    ipv6_default=False,
                    global_ipv6=False,
                    secure_settings=secure_settings,
                ),
                "name": "protected",
                "vpnInterface": "tun0",
            },
        ]
    else:
        phases = [
            {
                "capturedAtEpochMs": started_at + 700,
                "commands": _commands(
                    vpn_active=False,
                    lockdown_active=True,
                    ipv4_default=False,
                    ipv6_default=False,
                    global_ipv6=False,
                ),
                "name": "closed",
                "vpnInterface": "tun0",
            }
        ]
    return oracles.canonical_json_bytes(
        {
            "actionId": action_id,
            "correlationId": correlation_id,
            "phases": phases,
            "sourceSha": source_sha,
            "version": oracles.ROUTE_SNAPSHOT_VERSION,
            "windowFinishedAtEpochMs": finished_at,
            "windowStartedAtEpochMs": started_at,
        }
    )


def _udp_ipv4(
    source: str,
    destination: str,
    source_port: int,
    destination_port: int,
    payload: bytes,
) -> bytes:
    udp = (
        struct.pack("!HHHH", source_port, destination_port, 8 + len(payload), 0)
        + payload
    )
    header = bytearray(20)
    header[0] = 0x45
    header[2:4] = (20 + len(udp)).to_bytes(2, "big")
    header[8] = 64
    header[9] = 17
    header[12:16] = ipaddress.ip_address(source).packed
    header[16:20] = ipaddress.ip_address(destination).packed
    ethernet = b"\x02\x00\x00\x00\x00\x02\x02\x00\x00\x00\x00\x01\x08\x00"
    return ethernet + bytes(header) + udp


def _udp_ipv6(
    source: str,
    destination: str,
    source_port: int,
    destination_port: int,
    payload: bytes,
) -> bytes:
    udp = (
        struct.pack("!HHHH", source_port, destination_port, 8 + len(payload), 0)
        + payload
    )
    header = bytearray(40)
    header[0] = 0x60
    header[4:6] = len(udp).to_bytes(2, "big")
    header[6] = 17
    header[7] = 64
    header[8:24] = ipaddress.ip_address(source).packed
    header[24:40] = ipaddress.ip_address(destination).packed
    ethernet = b"\x02\x00\x00\x00\x00\x02\x02\x00\x00\x00\x00\x01\x86\xdd"
    return ethernet + bytes(header) + udp


def _pcap(records: list[tuple[int, bytes]]) -> bytes:
    output = bytearray(struct.pack("<IHHIIII", 0xA1B2C3D4, 2, 4, 0, 0, 65535, 1))
    for timestamp_ms, packet in records:
        seconds, millis = divmod(timestamp_ms, 1000)
        output.extend(
            struct.pack("<IIII", seconds, millis * 1000, len(packet), len(packet))
        )
        output.extend(packet)
    return bytes(output)


def packet_capture(
    action_id: str,
    *,
    correlation_id: str,
    started_at: int,
) -> bytes:
    marker_source = "192.0.2.201"
    records = [
        (
            started_at + 100,
            _udp_ipv4(
                marker_source,
                FIXTURE["markerAddress"],
                42000,
                FIXTURE["markerPort"],
                oracles._marker(action_id, correlation_id, "action"),
            ),
        )
    ]
    if action_id in oracles.TUNNEL_ACTIVITY_ACTIONS:
        records.append(
            (
                started_at + 600,
                _udp_ipv4(
                    "192.0.2.201",
                    FIXTURE["tunnelEndpoints"][0],
                    43000,
                    FIXTURE["tunnelPort"],
                    f"tunnel-control:{action_id}".encode("ascii"),
                ),
            )
        )
    records.append(
        (
            started_at + 900,
            _udp_ipv4(
                marker_source,
                FIXTURE["markerAddress"],
                42000,
                FIXTURE["markerPort"],
                oracles._marker(action_id, correlation_id, "outcome"),
            ),
        )
    )
    return _pcap(records)


def semantic_artifacts(
    action_id: str,
    *,
    correlation_id: str,
    source_sha: str,
    app_sha256: str,
    test_sha256: str,
    started_at: int,
    finished_at: int,
) -> dict[str, bytes]:
    return {
        "action-receipt": action_receipt(
            action_id,
            correlation_id=correlation_id,
            source_sha=source_sha,
            app_sha256=app_sha256,
            test_sha256=test_sha256,
            started_at=started_at,
            finished_at=finished_at,
        ),
        "packet-capture": packet_capture(
            action_id, correlation_id=correlation_id, started_at=started_at
        ),
        "route-snapshot": route_snapshot(
            action_id,
            correlation_id=correlation_id,
            source_sha=source_sha,
            started_at=started_at,
            finished_at=finished_at,
        ),
    }
