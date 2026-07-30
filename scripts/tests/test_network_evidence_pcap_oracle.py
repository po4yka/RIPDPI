from __future__ import annotations

import hashlib
import importlib.util
import ipaddress
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.ci import check_android_network_action_receipt as receipt_contract


ROOT = Path(__file__).resolve().parents[2]
TEST_READY_OVERRIDE = (
    ROOT / "scripts/tests/fixtures/android-network-evidence-test-ready-override.json"
)
ORACLE = ROOT / "test-lab/scripts/network-evidence-pcap-oracle.py"
ORACLE_SPEC = importlib.util.spec_from_file_location(
    "network_evidence_pcap_oracle", ORACLE
)
assert ORACLE_SPEC is not None and ORACLE_SPEC.loader is not None
oracle = importlib.util.module_from_spec(ORACLE_SPEC)
sys.modules[ORACLE_SPEC.name] = oracle
ORACLE_SPEC.loader.exec_module(oracle)
CORRELATION_ID = "c" * 64
SOURCE_SHA = "d" * 40
CLIENT_ARTIFACT_SHA256 = "e" * 64
TEST_ARTIFACT_SHA256 = "f" * 64
WINDOW_A = "oracle-fixture-window-a"
WINDOW_B = "oracle-fixture-window-b"
STARTUP_GATE = "killswitch-tun-establish-native-ready"
FIXTURE_ADDRESS = "198.51.100.20"
FIXTURE_CONTROL_PORT = 18080
FIXTURE_DNS_PORT = 15353
FIXTURE_DOT_PORT = 18006
FIXTURE_SOCKS_PORT = 18009
SOURCE_OWNED_ENCRYPTED_DNS_GATES = {
    "dns-virtual-vpn-resolver",
    "dns-proxied-through-tunnelled-resolver",
    "dns-no-isp-fallback-on-encrypted-resolver-outage",
}


def marker_preimage(window_id: str, kind: str, phase: str) -> bytes:
    window_id = {"window-a": WINDOW_A, "window-b": WINDOW_B}.get(window_id, window_id)
    return (
        f"ripdpi:network-evidence-marker:v2:{CORRELATION_ID}:{window_id}:{kind}:{phase}"
    ).encode("ascii")


def marker(window_id: str, kind: str, phase: str) -> bytes:
    return (
        "RIPDPI-EVIDENCE-V2:"
        + hashlib.sha256(marker_preimage(window_id, kind, phase)).hexdigest()
    ).encode("ascii")


def ethernet_ipv4_tcp(
    payload: bytes,
    *,
    source_port: int = 12345,
    destination_port: int = 443,
    sequence: int = 0,
    source_address: str = "192.0.2.10",
    destination_address: str = FIXTURE_ADDRESS,
) -> bytes:
    tcp = (
        struct.pack(
            "!HHIIHHHH",
            source_port,
            destination_port,
            sequence,
            0,
            5 << 12,
            0,
            0,
            0,
        )
        + payload
    )
    source = ipaddress.ip_address(source_address).packed
    destination = ipaddress.ip_address(destination_address).packed
    ipv4 = struct.pack(
        "!BBHHHBBH4s4s",
        0x45,
        0,
        20 + len(tcp),
        1,
        0,
        64,
        6,
        0,
        source,
        destination,
    )
    return b"\x00" * 12 + struct.pack("!H", 0x0800) + ipv4 + tcp


def ethernet_ipv4_udp(
    payload: bytes,
    *,
    source_port: int,
    destination_port: int,
    source_address: str = "192.0.2.10",
    destination_address: str = FIXTURE_ADDRESS,
) -> bytes:
    udp = (
        struct.pack("!HHHH", source_port, destination_port, 8 + len(payload), 0)
        + payload
    )
    source = ipaddress.ip_address(source_address).packed
    destination = ipaddress.ip_address(destination_address).packed
    ipv4 = struct.pack(
        "!BBHHHBBH4s4s",
        0x45,
        0,
        20 + len(udp),
        1,
        0,
        64,
        17,
        0,
        source,
        destination,
    )
    return b"\x00" * 12 + struct.pack("!H", 0x0800) + ipv4 + udp


def ethernet_ipv6_udp(
    payload: bytes,
    *,
    source_port: int = 12345,
    destination_port: int = 443,
    source_address: str = "2001:db8::10",
    destination_address: str = "2001:db8::20",
) -> bytes:
    udp = (
        struct.pack("!HHHH", source_port, destination_port, 8 + len(payload), 0)
        + payload
    )
    source = ipaddress.ip_address(source_address).packed
    destination = ipaddress.ip_address(destination_address).packed
    ipv6 = struct.pack("!IHBB16s16s", 6 << 28, len(udp), 17, 64, source, destination)
    return b"\x00" * 12 + struct.pack("!H", 0x86DD) + ipv6 + udp


def raw_ipv4_tcp(payload: bytes) -> bytes:
    return ethernet_ipv4_tcp(payload)[14:]


def linux_sll(protocol: int, network_packet: bytes) -> bytes:
    return struct.pack("!HHH8sH", 0, 1, 6, b"\x00" * 8, protocol) + network_packet


def linux_sll2(protocol: int, network_packet: bytes) -> bytes:
    return (
        struct.pack("!HHIHBB8s", protocol, 0, 1, 1, 0, 6, b"\x00" * 8) + network_packet
    )


def vlan_stack(
    network_protocol: int,
    network_packet: bytes,
    *,
    tag_protocols: tuple[int, ...],
) -> tuple[int, bytes]:
    protocol = network_protocol
    packet = network_packet
    for tag_protocol in reversed(tag_protocols):
        packet = struct.pack("!HH", 1, protocol) + packet
        protocol = tag_protocol
    return protocol, packet


def pcap_bytes(
    packets: list[tuple[int, int, bytes]],
    *,
    snaplen: int = 65535,
    linktype: int = 1,
    endian: str = "<",
    nanosecond: bool = False,
) -> bytes:
    magic = 0xA1B23C4D if nanosecond else 0xA1B2C3D4
    result = bytearray(
        struct.pack(f"{endian}IHHIIII", magic, 2, 4, 0, 0, snaplen, linktype)
    )
    for seconds, micros, packet in packets:
        captured = packet[:snaplen]
        result.extend(
            struct.pack(f"{endian}IIII", seconds, micros, len(captured), len(packet))
        )
        result.extend(captured)
    return bytes(result)


def pcap_packet_count(raw: bytes) -> int:
    endian = {
        b"\xd4\xc3\xb2\xa1": "<",
        b"\xa1\xb2\xc3\xd4": ">",
        b"\x4d\x3c\xb2\xa1": "<",
        b"\xa1\xb2\x3c\x4d": ">",
    }[raw[:4]]
    offset = 24
    count = 0
    while offset < len(raw):
        _seconds, _micros, captured_length, _original_length = struct.unpack_from(
            f"{endian}IIII", raw, offset
        )
        offset += 16 + captured_length
        count += 1
    if offset != len(raw):
        raise ValueError("test PCAP is malformed")
    return count


def pcap_records(raw: bytes) -> list[bytes]:
    endian = {
        b"\xd4\xc3\xb2\xa1": "<",
        b"\xa1\xb2\xc3\xd4": ">",
        b"\x4d\x3c\xb2\xa1": "<",
        b"\xa1\xb2\x3c\x4d": ">",
    }[raw[:4]]
    offset = 24
    records: list[bytes] = []
    while offset < len(raw):
        start = offset
        _seconds, _fraction, captured_length, _original_length = struct.unpack_from(
            f"{endian}IIII", raw, offset
        )
        offset += 16 + captured_length
        records.append(raw[start:offset])
    return records


def pcap_record(seconds: int, micros: int, packet: bytes) -> bytes:
    return struct.pack("<IIII", seconds, micros, len(packet), len(packet)) + packet


def tls_record(content_type: int, payload: bytes) -> bytes:
    return bytes((content_type, 3, 3)) + len(payload).to_bytes(2, "big") + payload


def dns_query_wire_size(query_host: str) -> int:
    return 12 + sum(1 + len(label) for label in query_host.split(".")) + 1 + 4


def redacted_event(event: dict[str, object]) -> dict[str, object]:
    def field_digest(field: str, value: str) -> str:
        return hashlib.sha256(
            f"ripdpi:network-evidence-fixture-event-field:v1:{field}:{value}".encode()
        ).hexdigest()

    return {
        "service": event["service"],
        "protocol": event["protocol"],
        "peerSha256": field_digest("peer", str(event["peer"])),
        "targetSha256": field_digest("target", str(event["target"])),
        "detailSha256": field_digest("detail", str(event["detail"])),
        "bytes": event["bytes"],
        "sniSha256": (
            None
            if event["sni"] is None
            else field_digest("sni", str(event["sni"]))
        ),
        "createdAt": event["createdAt"],
    }


def retime_pcap(raw: bytes, delta_micros: int) -> bytes:
    rewritten = bytearray(raw)
    offset = 24
    while offset < len(rewritten):
        seconds, micros, captured_length, _original_length = struct.unpack_from(
            "<IIII", rewritten, offset
        )
        micros += delta_micros
        seconds += micros // 1_000_000
        micros %= 1_000_000
        struct.pack_into("<II", rewritten, offset, seconds, micros)
        offset += 16 + captured_length
    return bytes(rewritten)


def rewrite_ethernet_ipv4_tcp_flow_source(
    raw: bytes,
    *,
    old_address: str,
    old_port: int,
    new_address: str,
    new_port: int,
) -> bytes:
    rewritten = bytearray(raw)
    old_ip = ipaddress.ip_address(old_address).packed
    new_ip = ipaddress.ip_address(new_address).packed
    offset = 24
    while offset < len(rewritten):
        _seconds, _micros, captured_length, _original_length = struct.unpack_from(
            "<IIII", rewritten, offset
        )
        frame = offset + 16
        if captured_length >= 54 and rewritten[frame + 12 : frame + 14] == b"\x08\x00":
            ip = frame + 14
            tcp = ip + (rewritten[ip] & 0x0F) * 4
            source = bytes(rewritten[ip + 12 : ip + 16])
            destination = bytes(rewritten[ip + 16 : ip + 20])
            source_port, destination_port = struct.unpack_from("!HH", rewritten, tcp)
            if source == old_ip and source_port == old_port:
                rewritten[ip + 12 : ip + 16] = new_ip
                struct.pack_into("!H", rewritten, tcp, new_port)
            elif destination == old_ip and destination_port == old_port:
                rewritten[ip + 16 : ip + 20] = new_ip
                struct.pack_into("!H", rewritten, tcp + 2, new_port)
        offset += 16 + captured_length
    return bytes(rewritten)


def add_observer_control(raw: bytes) -> bytes:
    endian = {
        b"\xd4\xc3\xb2\xa1": "<",
        b"\xa1\xb2\xc3\xd4": ">",
        b"\x4d\x3c\xb2\xa1": "<",
        b"\xa1\xb2\x3c\x4d": ">",
    }[raw[:4]]
    linktype = struct.unpack_from(f"{endian}I", raw, 20)[0]
    if linktype not in (1, 101, 113, 276):
        distinguished = bytearray(raw)
        struct.pack_into(f"{endian}I", distinguished, 12, 1)
        return bytes(distinguished)
    network_packet = raw_ipv4_tcp(b"observer-vantage-control")
    frame = {
        1: b"\x00" * 12 + struct.pack("!H", 0x0800) + network_packet,
        101: network_packet,
        113: linux_sll(0x0800, network_packet),
        276: linux_sll2(0x0800, network_packet),
    }[linktype]
    record = struct.pack(f"{endian}IIII", 102, 0, len(frame), len(frame)) + frame
    return raw + record


def capture_metadata(
    role: str, raw: bytes, *, packet_count: int | None = None
) -> dict[str, object]:
    return {
        "version": "network_evidence_private_capture_v1",
        "role": role,
        "correlationId": CORRELATION_ID,
        "captureStartedAtEpoch": 99,
        "captureFinishedAtEpoch": 103,
        "packetCount": pcap_packet_count(raw) if packet_count is None else packet_count,
        "rawCaptureSha256": hashlib.sha256(raw).hexdigest(),
    }


def canonical(value: object) -> bytes:
    return (json.dumps(value, separators=(",", ":"), sort_keys=True) + "\n").encode()


def fixture_manifest() -> dict[str, object]:
    return {
        "bindHost": "0.0.0.0",
        "androidHost": FIXTURE_ADDRESS,
        "tcpEchoPort": 18001,
        "udpEchoPort": 18002,
        "tlsEchoPort": 18003,
        "dnsUdpPort": FIXTURE_DNS_PORT,
        "dnsHttpPort": 18005,
        "dnsDotPort": 18006,
        "dnsDnscryptPort": 18007,
        "dnsDoqPort": 18008,
        "dnsOdohProxyPort": 18010,
        "dnsOdohTargetPort": 18011,
        "socks5Port": 18009,
        "controlPort": FIXTURE_CONTROL_PORT,
        "fixtureDomain": "fixture.test",
        "fixtureIpv4": FIXTURE_ADDRESS,
        "dnsAnswerIpv4": "203.0.113.9",
        "tlsCertificatePem": "fixture-certificate",
        "dnscryptProviderName": "2.dnscrypt-cert.fixture.test",
        "dnscryptPublicKey": "00" * 32,
    }


def fixture_identity(value: dict[str, object]) -> str:
    fields = (
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
    digest = hashlib.sha256()
    for item in (
        "ripdpi:fixture-identity:v1",
        *(part for field in fields for part in (field, str(value[field]))),
    ):
        encoded = item.encode()
        digest.update(struct.pack("!I", len(encoded)))
        digest.update(encoded)
    return digest.hexdigest()


def dns_packet(
    *,
    response: bool = False,
    transaction_id: int = 0x1234,
    rcode: int = 0,
    include_answer: bool = True,
) -> bytes:
    name = b"\x0bstartup-123\x07fixture\x04test\x00"
    flags = (0x8180 | rcode) if response else 0x0100
    answer_count = int(response and include_answer)
    header = struct.pack("!HHHHHH", transaction_id, flags, 1, answer_count, 0, 0)
    question = name + struct.pack("!HH", 1, 1)
    answer = b""
    if response and include_answer:
        answer = (
            b"\xc0\x0c"
            + struct.pack("!HHIH", 1, 1, 60, 4)
            + ipaddress.ip_address("203.0.113.9").packed
        )
    return header + question + answer


def dns_packet_for_name(
    name: str,
    *,
    response: bool = False,
    transaction_id: int = 0x1234,
) -> bytes:
    labels = name.rstrip(".").split(".")
    encoded_name = (
        b"".join(bytes((len(label),)) + label.encode("ascii") for label in labels)
        + b"\0"
    )
    flags = 0x8180 if response else 0x0100
    header = struct.pack("!HHHHHH", transaction_id, flags, 1, int(response), 0, 0)
    question = encoded_name + struct.pack("!HH", 1, 1)
    answer = b""
    if response:
        answer = (
            b"\xc0\x0c"
            + struct.pack("!HHIH", 1, 1, 60, 4)
            + ipaddress.ip_address("203.0.113.9").packed
        )
    return header + question + answer


def startup_receipt(fixture_sha: str) -> dict[str, object]:
    query_sha256 = hashlib.sha256(
        b"ripdpi:startup-dns-query:v1:startup-123.fixture.test"
    ).hexdigest()
    query_set = json.dumps(
        {"gateId": STARTUP_GATE, "queries": [["dnsQuerySha256", query_sha256]]},
        sort_keys=True,
        separators=(",", ":"),
    ).encode("ascii")
    return {
        "version": "android_network_evidence_action_receipt_v3",
        "status": "PASS",
        "gateId": STARTUP_GATE,
        "kind": "direct_window",
        "selector": "com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest#vpnStartupWindowHoldsDnsPacketUntilNativeReady",
        "semanticRule": "tun-establish-native-ready-v1",
        "correlationId": CORRELATION_ID,
        "sourceSha": SOURCE_SHA,
        "clientArtifactSha256": CLIENT_ARTIFACT_SHA256,
        "testArtifactSha256": TEST_ARTIFACT_SHA256,
        "fixtureIdentitySha256": fixture_sha,
        "actionMarkerSha256": hashlib.sha256(
            marker_preimage(STARTUP_GATE, "direct_window", "action")
        ).hexdigest(),
        "outcomeMarkerSha256": hashlib.sha256(
            marker_preimage(STARTUP_GATE, "direct_window", "outcome")
        ).hexdigest(),
        "querySetSha256": hashlib.sha256(
            b"ripdpi:network-evidence-query-set:v1:" + query_set
        ).hexdigest(),
        "startedAtElapsedRealtimeMs": 100,
        "actionMarkerAtElapsedRealtimeMs": 120,
        "outcomeMarkerAtElapsedRealtimeMs": 300,
        "finishedAtElapsedRealtimeMs": 500,
        "appUid": 10101,
        "testUid": 10102,
        "actionMarkerPid": 201,
        "actionMarkerUid": 10101,
        "outcomeMarkerPid": 201,
        "outcomeMarkerUid": 10101,
        "dnsProbePids": [301],
        "dnsProbeUid": 10102,
        "facts": {
            "tunFd": 41,
            "closedWindowRunningCount": 0,
            "preReadyDnsEventCount": 0,
            "startupWindowAssertionElapsedMs": 300,
            "dnsRcode": 0,
            "dnsQuerySha256": query_sha256,
            "dnsAnswersExact": True,
            "postReadyDnsEventCount": 1,
            "txPackets": 2,
            "rxPackets": 1,
            "finalStatus": "Halted",
        },
    }


class NetworkEvidencePcapOracleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.client_pcap = self.root / "client.pcap"
        self.observer_pcap = self.root / "observer.pcap"
        self.client_metadata = self.root / "client-metadata.json"
        self.observer_metadata = self.root / "observer-metadata.json"
        self.ledger_path = self.root / "ledger.json"
        self.action_receipt = self.root / "action-receipt.json"
        self.fixture_transcript = self.root / "fixture-transcript.json"
        self.fixture_manifest = self.root / "fixture-manifest.json"
        self.client_output = self.root / "client-observation.json"
        self.observer_output = self.root / "observer-observation.json"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_inputs(self) -> dict[str, object]:
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                200_000,
                ethernet_ipv6_udp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        raw = pcap_bytes(packets)
        self.client_pcap.write_bytes(raw)
        self.observer_pcap.write_bytes(add_observer_control(raw))
        plan = {
            "version": "network_evidence_scenario_plan_v3",
            "sourceSha": SOURCE_SHA,
            "correlationId": CORRELATION_ID,
            "clientArtifactSha256": CLIENT_ARTIFACT_SHA256,
            "testArtifactSha256": TEST_ARTIFACT_SHA256,
            "windows": [
                {
                    "id": WINDOW_A,
                    "kind": "direct_window",
                    "startedAtEpoch": 100,
                    "finishedAtEpoch": 101,
                    "actionMarkerSha256": hashlib.sha256(
                        marker_preimage("window-a", "direct_window", "action")
                    ).hexdigest(),
                    "outcomeMarkerSha256": hashlib.sha256(
                        marker_preimage("window-a", "direct_window", "outcome")
                    ).hexdigest(),
                }
            ],
        }
        ledger = {
            "version": "network_evidence_action_ledger_v2",
            "scenarioPlan": plan,
            "semanticRules": [{"windowId": WINDOW_A, "rule": "generic-marker-pair"}],
            "captures": {
                "client-underlay": {
                    "rawCaptureSha256": hashlib.sha256(
                        self.client_pcap.read_bytes()
                    ).hexdigest()
                },
                "external-observer": {
                    "rawCaptureSha256": hashlib.sha256(
                        self.observer_pcap.read_bytes()
                    ).hexdigest()
                },
            },
        }
        self.ledger_path.write_bytes(canonical(ledger))
        self.write_metadata()
        return ledger

    def write_startup_inputs(self) -> dict[str, object]:
        action = marker(STARTUP_GATE, "direct_window", "action")
        outcome = marker(STARTUP_GATE, "direct_window", "outcome")
        split = len(action) // 2

        def packets(
            source_address: str, source_port: int
        ) -> list[tuple[int, int, bytes]]:
            return [
                (
                    100,
                    100_000,
                    ethernet_ipv4_tcp(
                        action[:split],
                        source_port=source_port,
                        destination_port=FIXTURE_CONTROL_PORT,
                        sequence=100,
                        source_address=source_address,
                    ),
                ),
                (
                    100,
                    110_000,
                    ethernet_ipv4_tcp(
                        action[split:],
                        source_port=source_port,
                        destination_port=FIXTURE_CONTROL_PORT,
                        sequence=100 + split,
                        source_address=source_address,
                    ),
                ),
                (
                    100,
                    150_000,
                    ethernet_ipv4_tcp(
                        b"fixture-control-event-check",
                        source_port=source_port + 1,
                        destination_port=FIXTURE_CONTROL_PORT,
                        sequence=400,
                        source_address=source_address,
                    ),
                ),
                (
                    100,
                    200_000,
                    ethernet_ipv4_udp(
                        dns_packet(),
                        source_port=source_port + 3,
                        destination_port=FIXTURE_DNS_PORT,
                        source_address=source_address,
                    ),
                ),
                (
                    100,
                    210_000,
                    ethernet_ipv4_udp(
                        dns_packet(response=True),
                        source_port=FIXTURE_DNS_PORT,
                        destination_port=source_port + 3,
                        source_address=FIXTURE_ADDRESS,
                        destination_address=source_address,
                    ),
                ),
                (
                    100,
                    300_000,
                    ethernet_ipv4_tcp(
                        outcome,
                        source_port=source_port + 2,
                        destination_port=FIXTURE_CONTROL_PORT,
                        sequence=700,
                        source_address=source_address,
                    ),
                ),
            ]

        self.client_pcap.write_bytes(pcap_bytes(packets("192.0.2.10", 20000)))
        self.observer_pcap.write_bytes(pcap_bytes(packets("203.0.113.77", 30000)))
        fixture = fixture_manifest()
        fixture_sha = fixture_identity(fixture)
        self.fixture_manifest.write_bytes(canonical(fixture))
        os.chmod(self.fixture_manifest, 0o600)
        receipt_raw = canonical(startup_receipt(fixture_sha))
        self.action_receipt.write_bytes(receipt_raw)
        os.chmod(self.action_receipt, 0o600)
        plan = {
            "version": "network_evidence_scenario_plan_v3",
            "sourceSha": SOURCE_SHA,
            "correlationId": CORRELATION_ID,
            "clientArtifactSha256": CLIENT_ARTIFACT_SHA256,
            "testArtifactSha256": TEST_ARTIFACT_SHA256,
            "windows": [
                {
                    "id": STARTUP_GATE,
                    "kind": "direct_window",
                    "startedAtEpoch": 100,
                    "finishedAtEpoch": 101,
                    "actionMarkerSha256": hashlib.sha256(
                        marker_preimage(STARTUP_GATE, "direct_window", "action")
                    ).hexdigest(),
                    "outcomeMarkerSha256": hashlib.sha256(
                        marker_preimage(STARTUP_GATE, "direct_window", "outcome")
                    ).hexdigest(),
                }
            ],
        }
        ledger = {
            "version": "network_evidence_action_ledger_v2",
            "scenarioPlan": plan,
            "semanticRules": [
                {
                    "windowId": STARTUP_GATE,
                    "rule": "tun-establish-native-ready-v1",
                    "actionReceiptSha256": hashlib.sha256(receipt_raw).hexdigest(),
                    "fixtureIdentitySha256": fixture_sha,
                }
            ],
            "captures": {
                "client-underlay": {
                    "rawCaptureSha256": hashlib.sha256(
                        self.client_pcap.read_bytes()
                    ).hexdigest()
                },
                "external-observer": {
                    "rawCaptureSha256": hashlib.sha256(
                        self.observer_pcap.read_bytes()
                    ).hexdigest()
                },
            },
        }
        self.ledger_path.write_bytes(canonical(ledger))
        self.write_metadata()
        return ledger

    def write_dns_action_inputs(
        self,
        gate_id: str,
        *,
        forged_response_address: str | None = None,
        forged_response_port: int | None = None,
        observer_outbound_tls: bytes | None = None,
    ) -> dict[str, object]:
        self.fixture_transcript.unlink(missing_ok=True)
        descriptor = receipt_contract.load_action_registry()[gate_id]
        facts = receipt_contract.example_valid_facts(gate_id)
        query_fields = sorted(
            field for field in facts if field.lower().endswith("querysha256")
        )
        source_owned = gate_id in SOURCE_OWNED_ENCRYPTED_DNS_GATES
        query_names = {
            field: (
                f"{gate_id.removeprefix('dns-')[:24]}-{CORRELATION_ID[:16]}.fixture.test"
                if source_owned
                else f"q{index}.{gate_id[:24]}.fixture.test"
            )
            for index, field in enumerate(query_fields, start=1)
        }
        for field, name in query_names.items():
            facts[field] = hashlib.sha256(
                b"ripdpi:dns-evidence-query:v1:" + name.encode("ascii")
            ).hexdigest()
        query_wire_bytes = dns_query_wire_size(next(iter(query_names.values())))
        valid_outbound_tls = tls_record(22, b"test") + tls_record(
            23, b"q" * (query_wire_bytes + 2)
        )
        valid_inbound_tls = tls_record(22, b"ok")
        endpoint_sha256 = (
            hashlib.sha256(
                f"ripdpi:dns-evidence-resolver:v1:dot:{FIXTURE_ADDRESS}:{FIXTURE_DOT_PORT}".encode(
                    "ascii"
                )
            ).hexdigest()
            if source_owned
            else hashlib.sha256(
                b"ripdpi:dns-evidence-resolver:v1:"
                + ipaddress.ip_address(FIXTURE_ADDRESS).packed
                + struct.pack("!H", FIXTURE_DNS_PORT)
            ).hexdigest()
        )
        address_sha256 = hashlib.sha256(
            b"ripdpi:dns-evidence-address:v1:"
            + ipaddress.ip_address(FIXTURE_ADDRESS).packed
        ).hexdigest()
        for field in (
            "tunnelResolverProviderSha256",
            "resolverProviderSha256",
            "directResolverSha256",
            "tunnelResolverSha256",
            "observedBootstrapResolverSha256",
            "encryptedResolverProviderSha256",
            "privateDnsProviderSha256",
        ):
            if field in facts:
                facts[field] = endpoint_sha256
        if "virtualDnsAddressSha256" in facts:
            facts["virtualDnsAddressSha256"] = (
                hashlib.sha256(
                    b"ripdpi:dns-evidence-virtual-dns:v1:198.18.0.53"
                ).hexdigest()
                if source_owned
                else address_sha256
            )
        if gate_id == "dns-no-isp-fallback-on-encrypted-resolver-outage":
            facts["faultControlSha256"] = hashlib.sha256(
                b"ripdpi:dns-evidence-fault:v1:dns_dot:dns_timeout:persistent"
            ).hexdigest()
        if "allowlistedResolverSetSha256" in facts:
            facts["allowlistedResolverSetSha256"] = hashlib.sha256(
                b"ripdpi:dns-evidence-resolver-set:v1:"
                + endpoint_sha256.encode("ascii")
            ).hexdigest()
        query_set_sha256 = receipt_contract.query_set_sha256(gate_id, facts)
        fixture = fixture_manifest()
        fixture_sha = fixture_identity(fixture)
        self.fixture_manifest.write_bytes(canonical(fixture))
        os.chmod(self.fixture_manifest, 0o600)
        if source_owned:
            query_host = next(iter(query_names.values()))
            observer_flow_peer = "203.0.113.77:30001"
            query_peer = (
                f"{FIXTURE_ADDRESS}:24000"
                if gate_id == "dns-proxied-through-tunnelled-resolver"
                else observer_flow_peer
            )
            events = [
                {
                    "service": "dns_dot",
                    "protocol": "dot",
                    "peer": query_peer,
                    "target": f"{FIXTURE_ADDRESS}:{FIXTURE_DOT_PORT}",
                    "detail": query_host,
                    "bytes": query_wire_bytes,
                    "sni": "fixture.test",
                    "createdAt": 100_150,
                }
            ]
            if gate_id == "dns-proxied-through-tunnelled-resolver":
                socks_target = f"{FIXTURE_ADDRESS}:{FIXTURE_DOT_PORT}"
                events.insert(
                    0,
                    {
                        "service": "socks5_relay",
                        "protocol": "tcp",
                        "peer": observer_flow_peer,
                        "target": f"{FIXTURE_ADDRESS}:{FIXTURE_SOCKS_PORT}",
                        "detail": socks_target,
                        "bytes": 32,
                        "sni": None,
                        "createdAt": 100_130,
                    },
                )
                facts["socksTargetSha256"] = hashlib.sha256(
                    f"ripdpi:dns-evidence-socks-target:v1:{socks_target}".encode(
                        "utf-8"
                    )
                ).hexdigest()
            if gate_id == "dns-no-isp-fallback-on-encrypted-resolver-outage":
                events.append(
                    {
                        "service": "dns_dot",
                        "protocol": "dot",
                        "peer": query_peer,
                        "target": f"{FIXTURE_ADDRESS}:{FIXTURE_DOT_PORT}",
                        "detail": "fault:DnsTimeout",
                        "bytes": query_wire_bytes,
                        "sni": "fixture.test",
                        "createdAt": 100_160,
                    }
                )
            accept_event = {
                "service": "dns_dot",
                "protocol": "dot",
                "peer": query_peer,
                "target": f"{FIXTURE_ADDRESS}:{FIXTURE_DOT_PORT}",
                "detail": "accept",
                "bytes": 0,
                "sni": None,
                "createdAt": 100_140,
            }
            inventory_events = list(events)
            query_index = next(
                index
                for index, event in enumerate(inventory_events)
                if event["detail"] == query_host
            )
            inventory_events.insert(query_index, accept_event)
            transcript = {
                "version": "network_evidence_fixture_transcript_v2",
                "gateId": gate_id,
                "correlationId": CORRELATION_ID,
                "fixtureIdentitySha256": fixture_sha,
                "queryHost": query_host,
                "querySha256": facts[next(iter(query_fields))],
                "events": events,
                "eventInventory": [
                    redacted_event(event) for event in inventory_events
                ],
            }
            transcript_raw = canonical(transcript)
            self.fixture_transcript.write_bytes(transcript_raw)
            os.chmod(self.fixture_transcript, 0o600)
            facts["fixtureTranscriptSha256"] = hashlib.sha256(
                transcript_raw
            ).hexdigest()
        action_sha = hashlib.sha256(
            marker_preimage(gate_id, "dns", "action")
        ).hexdigest()
        outcome_sha = hashlib.sha256(
            marker_preimage(gate_id, "dns", "outcome")
        ).hexdigest()
        receipt = {
            "version": receipt_contract.VERSION,
            "status": "PASS",
            "gateId": gate_id,
            "kind": "dns",
            "selector": descriptor.selector,
            "semanticRule": descriptor.semantic_rule,
            "correlationId": CORRELATION_ID,
            "sourceSha": SOURCE_SHA,
            "clientArtifactSha256": CLIENT_ARTIFACT_SHA256,
            "testArtifactSha256": TEST_ARTIFACT_SHA256,
            "fixtureIdentitySha256": fixture_sha,
            "actionMarkerSha256": action_sha,
            "outcomeMarkerSha256": outcome_sha,
            "querySetSha256": query_set_sha256,
            "startedAtElapsedRealtimeMs": 100,
            "actionMarkerAtElapsedRealtimeMs": 110,
            "outcomeMarkerAtElapsedRealtimeMs": 300,
            "finishedAtElapsedRealtimeMs": 400,
            "appUid": 10101,
            "testUid": 10102,
            "actionMarkerPid": 201,
            "actionMarkerUid": 10101,
            "outcomeMarkerPid": 201,
            "outcomeMarkerUid": 10101,
            "dnsProbePids": [301],
            "dnsProbeUid": 10102,
            "facts": facts,
        }
        receipt_raw = canonical(receipt)
        self.action_receipt.write_bytes(receipt_raw)
        os.chmod(self.action_receipt, 0o600)

        fail_closed = descriptor.semantic_rule in {
            "encrypted-outage-fail-closed-v1",
            "core-crash-dns-fail-closed-v1",
            "android-private-dns-conflict-v1",
        }

        def packets(
            source_address: str,
            source_port: int,
            timestamp_offset_us: int,
            outbound_tls: bytes,
        ) -> list[tuple[int, int, bytes]]:
            result = [
                (
                    100,
                    100_000,
                    ethernet_ipv4_tcp(
                        marker(gate_id, "dns", "action"),
                        source_port=source_port,
                        destination_port=FIXTURE_CONTROL_PORT,
                        source_address=source_address,
                    ),
                )
            ]
            if source_owned:
                endpoint_port = (
                    FIXTURE_SOCKS_PORT
                    if gate_id == "dns-proxied-through-tunnelled-resolver"
                    else FIXTURE_DOT_PORT
                )
                inbound_tls = valid_inbound_tls
                if gate_id == "dns-proxied-through-tunnelled-resolver":
                    outbound_tls = (
                        b"\x05\x01\x00"
                        + b"\x05\x01\x00\x01"
                        + ipaddress.ip_address(FIXTURE_ADDRESS).packed
                        + struct.pack("!H", FIXTURE_DOT_PORT)
                        + outbound_tls
                    )
                    inbound_tls = (
                        b"\x05\x00"
                        + b"\x05\x00\x00\x01\x00\x00\x00\x00\x00\x00"
                        + inbound_tls
                    )
                result.extend(
                    [
                        (
                            100,
                            140_000 + timestamp_offset_us,
                            ethernet_ipv4_tcp(
                                outbound_tls,
                                source_port=source_port + 1,
                                destination_port=endpoint_port,
                                source_address=source_address,
                            ),
                        ),
                        (
                            100,
                            160_000
                            + timestamp_offset_us
                            + (50 if source_address == "203.0.113.77" else 0),
                            ethernet_ipv4_tcp(
                                inbound_tls,
                                source_port=endpoint_port,
                                destination_port=source_port + 1,
                                source_address=FIXTURE_ADDRESS,
                                destination_address=source_address,
                            ),
                        ),
                    ]
                )
            for index, name in (
                enumerate(query_names.values(), start=1) if not source_owned else ()
            ):
                transaction_id = 0x1200 + index
                query_field = query_fields[index - 1]
                is_ipv6_query = query_field == "ipv6QuerySha256"
                query_frame = (
                    ethernet_ipv6_udp(
                        dns_packet_for_name(name, transaction_id=transaction_id),
                        source_port=source_port + index,
                        destination_port=FIXTURE_DNS_PORT,
                    )
                    if is_ipv6_query
                    else ethernet_ipv4_udp(
                        dns_packet_for_name(name, transaction_id=transaction_id),
                        source_port=source_port + index,
                        destination_port=FIXTURE_DNS_PORT,
                        source_address=source_address,
                    )
                )
                result.append(
                    (
                        100,
                        120_000 + index * 10_000,
                        query_frame,
                    )
                )
                if not fail_closed:
                    response_frame = (
                        ethernet_ipv6_udp(
                            dns_packet_for_name(
                                name, response=True, transaction_id=transaction_id
                            ),
                            source_port=forged_response_port or FIXTURE_DNS_PORT,
                            destination_port=source_port + index,
                            source_address="2001:db8::20",
                            destination_address="2001:db8::10",
                        )
                        if is_ipv6_query
                        else ethernet_ipv4_udp(
                            dns_packet_for_name(
                                name, response=True, transaction_id=transaction_id
                            ),
                            source_port=forged_response_port or FIXTURE_DNS_PORT,
                            destination_port=source_port + index,
                            source_address=forged_response_address or FIXTURE_ADDRESS,
                            destination_address=source_address,
                        )
                    )
                    result.append(
                        (
                            100,
                            125_000 + index * 10_000,
                            response_frame,
                        )
                    )
            result.append(
                (
                    100,
                    300_000,
                    ethernet_ipv4_tcp(
                        marker(gate_id, "dns", "outcome"),
                        source_port=source_port + 20,
                        destination_port=FIXTURE_CONTROL_PORT,
                        source_address=source_address,
                    ),
                )
            )
            return result

        self.client_pcap.write_bytes(
            pcap_bytes(
                packets(
                    "192.0.2.10",
                    20000,
                    0,
                    valid_outbound_tls,
                )
            )
        )
        self.observer_pcap.write_bytes(
            pcap_bytes(
                packets(
                    "203.0.113.77",
                    30000,
                    100,
                    observer_outbound_tls or valid_outbound_tls,
                )
            )
        )
        plan = {
            "version": "network_evidence_scenario_plan_v3",
            "sourceSha": SOURCE_SHA,
            "correlationId": CORRELATION_ID,
            "clientArtifactSha256": CLIENT_ARTIFACT_SHA256,
            "testArtifactSha256": TEST_ARTIFACT_SHA256,
            "windows": [
                {
                    "id": gate_id,
                    "kind": "dns",
                    "startedAtEpoch": 100,
                    "finishedAtEpoch": 101,
                    "actionMarkerSha256": action_sha,
                    "outcomeMarkerSha256": outcome_sha,
                }
            ],
        }
        ledger = {
            "version": "network_evidence_action_ledger_v2",
            "scenarioPlan": plan,
            "semanticRules": [
                {
                    "windowId": gate_id,
                    "rule": descriptor.semantic_rule,
                    "actionReceiptSha256": hashlib.sha256(receipt_raw).hexdigest(),
                    "fixtureIdentitySha256": fixture_sha,
                }
            ],
            "captures": {
                "client-underlay": {
                    "rawCaptureSha256": hashlib.sha256(
                        self.client_pcap.read_bytes()
                    ).hexdigest()
                },
                "external-observer": {
                    "rawCaptureSha256": hashlib.sha256(
                        self.observer_pcap.read_bytes()
                    ).hexdigest()
                },
            },
        }
        self.ledger_path.write_bytes(canonical(ledger))
        self.write_metadata()
        return ledger

    def write_metadata(self) -> None:
        self.client_metadata.write_bytes(
            canonical(
                capture_metadata("client-underlay", self.client_pcap.read_bytes())
            )
        )
        self.observer_metadata.write_bytes(
            canonical(
                capture_metadata("external-observer", self.observer_pcap.read_bytes())
            )
        )

    def run_oracle(
        self,
        *,
        test_ready_override: bool = True,
        force_fixture_transcript: bool = False,
    ) -> subprocess.CompletedProcess[str]:
        command = [
            sys.executable,
            str(ORACLE),
            "--client-pcap",
            str(self.client_pcap),
            "--observer-pcap",
            str(self.observer_pcap),
            "--client-metadata",
            str(self.client_metadata),
            "--observer-metadata",
            str(self.observer_metadata),
            "--ledger",
            str(self.ledger_path),
        ]
        if self.action_receipt.exists() or self.fixture_manifest.exists():
            command.extend(
                [
                    "--action-receipt",
                    str(self.action_receipt),
                    "--fixture-manifest",
                    str(self.fixture_manifest),
                ]
            )
            if test_ready_override:
                command.extend(
                    [
                        "--test-only-action-registry-override",
                        str(TEST_READY_OVERRIDE),
                    ]
                )
            if self.fixture_transcript.exists():
                command.extend(["--fixture-transcript", str(self.fixture_transcript)])
        elif force_fixture_transcript:
            command.extend(["--fixture-transcript", str(self.fixture_transcript)])
        command.extend(
            [
                "--client-output",
                str(self.client_output),
                "--observer-output",
                str(self.observer_output),
            ]
        )
        return subprocess.run(
            command,
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def load_ledger(self) -> dict[str, object]:
        return json.loads(self.ledger_path.read_text(encoding="utf-8"))

    def save_ledger(self, ledger: dict[str, object]) -> None:
        if self.client_pcap.read_bytes() == self.observer_pcap.read_bytes():
            self.observer_pcap.write_bytes(
                add_observer_control(self.observer_pcap.read_bytes())
            )
        captures = ledger["captures"]
        assert isinstance(captures, dict)
        captures["client-underlay"]["rawCaptureSha256"] = hashlib.sha256(
            self.client_pcap.read_bytes()
        ).hexdigest()
        captures["external-observer"]["rawCaptureSha256"] = hashlib.sha256(
            self.observer_pcap.read_bytes()
        ).hexdigest()
        self.ledger_path.write_bytes(canonical(ledger))
        self.write_metadata()

    def rewrite_transcript_and_bind_receipt(
        self,
        ledger: dict[str, object],
        transcript: dict[str, object],
        *,
        refresh_inventory: bool = True,
    ) -> None:
        if refresh_inventory:
            accept_detail = hashlib.sha256(
                b"ripdpi:network-evidence-fixture-event-field:v1:detail:accept"
            ).hexdigest()
            prior_accept = next(
                item
                for item in transcript["eventInventory"]
                if item["detailSha256"] == accept_detail
            )
            query_event = next(
                event
                for event in transcript["events"]
                if event["detail"] == transcript["queryHost"]
            )
            accept_event = {
                "service": "dns_dot",
                "protocol": "dot",
                "peer": query_event["peer"],
                "target": query_event["target"],
                "detail": "accept",
                "bytes": 0,
                "sni": None,
                "createdAt": prior_accept["createdAt"],
            }
            inventory_events = list(transcript["events"])
            query_index = inventory_events.index(query_event)
            inventory_events.insert(query_index, accept_event)
            transcript["eventInventory"] = [
                redacted_event(event) for event in inventory_events
            ]
        transcript_raw = canonical(transcript)
        self.fixture_transcript.write_bytes(transcript_raw)
        receipt = json.loads(self.action_receipt.read_text(encoding="utf-8"))
        receipt["facts"]["fixtureTranscriptSha256"] = hashlib.sha256(
            transcript_raw
        ).hexdigest()
        receipt_raw = canonical(receipt)
        self.action_receipt.write_bytes(receipt_raw)
        ledger["semanticRules"][0]["actionReceiptSha256"] = hashlib.sha256(
            receipt_raw
        ).hexdigest()
        self.ledger_path.write_bytes(canonical(ledger))

    def add_window_b(self, ledger: dict[str, object]) -> None:
        plan = ledger["scenarioPlan"]
        assert isinstance(plan, dict)
        windows = plan["windows"]
        assert isinstance(windows, list)
        windows.append(
            {
                "id": WINDOW_B,
                "kind": "dns",
                "startedAtEpoch": 101,
                "finishedAtEpoch": 102,
                "actionMarkerSha256": hashlib.sha256(
                    marker_preimage("window-b", "dns", "action")
                ).hexdigest(),
                "outcomeMarkerSha256": hashlib.sha256(
                    marker_preimage("window-b", "dns", "outcome")
                ).hexdigest(),
            }
        )
        rules = ledger["semanticRules"]
        assert isinstance(rules, list)
        rules.append({"windowId": WINDOW_B, "rule": "generic-marker-pair"})

    def test_happy_two_vantage_marker_case_writes_canonical_private_outputs(
        self,
    ) -> None:
        ledger = self.write_inputs()

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        plan_sha256 = hashlib.sha256(canonical(ledger["scenarioPlan"])).hexdigest()
        for role, output, capture in (
            ("client-underlay", self.client_output, self.client_pcap),
            ("external-observer", self.observer_output, self.observer_pcap),
        ):
            observation = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(observation["role"], role)
            self.assertEqual(observation["scenarioPlanSha256"], plan_sha256)
            self.assertEqual(
                observation["rawCaptureSha256"],
                hashlib.sha256(capture.read_bytes()).hexdigest(),
            )
            self.assertEqual(observation["captureStartedAtEpoch"], 99)
            self.assertEqual(observation["captureFinishedAtEpoch"], 103)
            self.assertEqual(observation["windows"][0]["expectedPacketCount"], 2)
            self.assertEqual(observation["windows"][0]["unexpectedPacketCount"], 0)
            self.assertEqual(observation["windows"][0]["captureErrorCount"], 0)
            self.assertEqual(observation["windows"][0]["actionObservedCount"], 1)
            self.assertEqual(observation["windows"][0]["outcomeObservedCount"], 1)
            self.assertEqual(output.read_bytes(), canonical(observation))
            self.assertEqual(os.stat(output).st_mode & 0o777, 0o600)

    def test_fixture_transcript_is_forbidden_without_action_semantics(self) -> None:
        self.write_inputs()
        self.fixture_transcript.write_text("{}", encoding="utf-8")

        result = self.run_oracle(force_fixture_transcript=True)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture transcript", result.stderr)

    def test_startup_rule_accepts_segmented_markers_nat_and_post_ready_dns(
        self,
    ) -> None:
        self.write_startup_inputs()

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            observation = json.loads(output.read_text(encoding="utf-8"))
            window = observation["windows"][0]
            self.assertEqual(window["id"], STARTUP_GATE)
            self.assertEqual(window["unexpectedPacketCount"], 0)
            self.assertEqual(window["captureErrorCount"], 0)
            published = output.read_bytes()
            for private_value in (
                b"192.0.2.10",
                b"203.0.113.77",
                b"startup.fixture.test",
                dns_packet(),
            ):
                self.assertNotIn(private_value, published)

    def test_all_dns_rules_dispatch_to_exact_packet_semantics(self) -> None:
        dns_gates = [
            gate_id
            for gate_id, descriptor in receipt_contract.load_action_registry().items()
            if descriptor.kind == "dns"
        ]
        self.assertEqual(len(dns_gates), 7)
        for gate_id in dns_gates:
            with self.subTest(gate_id=gate_id):
                self.write_dns_action_inputs(gate_id)
                result = self.run_oracle()
                self.assertEqual(result.returncode, 0, result.stderr)
                for output in (self.client_output, self.observer_output):
                    window = json.loads(output.read_text(encoding="utf-8"))["windows"][
                        0
                    ]
                    self.assertEqual(window["id"], gate_id)
                    self.assertEqual(window["unexpectedPacketCount"], 0)
                    self.assertEqual(window["captureErrorCount"], 0)

    def test_production_registry_validates_ready_dns_contract_fixture(self) -> None:
        self.write_dns_action_inputs("dns-virtual-vpn-resolver")

        result = self.run_oracle(test_ready_override=False)

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_dns_rule_rejects_forged_response_source_address(self) -> None:
        self.write_dns_action_inputs(
            "dns-allowlisted-bootstrap-resolution",
            forged_response_address="203.0.113.200",
        )

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing an exact packet-parsed DNS response", result.stderr)

    def test_dns_rule_rejects_forged_response_source_port(self) -> None:
        self.write_dns_action_inputs(
            "dns-allowlisted-bootstrap-resolution", forged_response_port=5353
        )

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing an exact packet-parsed DNS response", result.stderr)

    def test_dns_parser_rejects_trailing_hidden_bytes(self) -> None:
        with self.assertRaisesRegex(ValueError, "trailing unparsed bytes"):
            oracle._dns_packet_facts(dns_packet_for_name("q.fixture.test") + b"hidden")

    def test_dns_rule_rejects_plaintext_leak_on_either_vantage(self) -> None:
        gate_id = "dns-no-isp-fallback-on-encrypted-resolver-outage"
        for leaking_role in ("client-underlay", "external-observer"):
            with self.subTest(leaking_role=leaking_role):
                ledger = self.write_dns_action_inputs(gate_id)
                capture = (
                    self.client_pcap
                    if leaking_role == "client-underlay"
                    else self.observer_pcap
                )
                leak = ethernet_ipv4_udp(
                    dns_packet_for_name("leak.fixture.test", transaction_id=0x9999),
                    source_port=25000,
                    destination_port=53,
                )
                raw = capture.read_bytes()
                records = pcap_records(raw)
                record = struct.pack("<IIII", 100, 200_000, len(leak), len(leak)) + leak
                capture.write_bytes(
                    raw[:24] + b"".join(records[:-1] + [record, records[-1]])
                )
                self.save_ledger(ledger)

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("contains plaintext DNS", result.stderr)

    def test_dns_rule_rejects_wrong_packet_observed_provider(self) -> None:
        gate_id = "dns-proxied-through-tunnelled-resolver"
        ledger = self.write_dns_action_inputs(gate_id)
        receipt = json.loads(self.action_receipt.read_text(encoding="utf-8"))
        receipt["facts"]["resolverProviderSha256"] = "9" * 64
        receipt_raw = canonical(receipt)
        self.action_receipt.write_bytes(receipt_raw)
        ledger["semanticRules"][0]["actionReceiptSha256"] = hashlib.sha256(
            receipt_raw
        ).hexdigest()
        self.ledger_path.write_bytes(canonical(ledger))

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("resolver provider digest mismatch", result.stderr)

    def test_encrypted_dns_rule_rejects_unbound_transcript_bytes(self) -> None:
        self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"][0]["createdAt"] = 99
        self.fixture_transcript.write_bytes(canonical(transcript))

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture transcript digest mismatch", result.stderr)

    def test_encrypted_dns_rule_rejects_duplicate_identity_bound_query(self) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"].append(dict(transcript["events"][0]))
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactly one DoT query transcript", result.stderr)

    def test_proxied_dns_rule_rejects_missing_socks_target_event(self) -> None:
        ledger = self.write_dns_action_inputs("dns-proxied-through-tunnelled-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"] = [
            event
            for event in transcript["events"]
            if event["service"] != "socks5_relay"
        ]
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exact fixture SOCKS target transcript is missing", result.stderr)

    def test_outage_dns_rule_rejects_missing_deterministic_fault(self) -> None:
        ledger = self.write_dns_action_inputs(
            "dns-no-isp-fallback-on-encrypted-resolver-outage"
        )
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"] = [
            event
            for event in transcript["events"]
            if not str(event["detail"]).startswith("fault:")
        ]
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("deterministic DoT timeout transcript is missing", result.stderr)

    def test_encrypted_dns_transcript_binds_query_target_sni_bytes_and_peer(
        self,
    ) -> None:
        mutations = {
            "target": ("203.0.113.9:18006", "target, SNI, or byte count mismatch"),
            "sni": ("wrong.fixture.test", "target, SNI, or byte count mismatch"),
            "bytes": (0, "target, SNI, or byte count mismatch"),
            "peer": ("not-an-endpoint", "query peer is malformed"),
        }
        for field, (replacement, expected_error) in mutations.items():
            with self.subTest(field=field):
                ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
                transcript = json.loads(
                    self.fixture_transcript.read_text(encoding="utf-8")
                )
                transcript["events"][0][field] = replacement
                self.rewrite_transcript_and_bind_receipt(ledger, transcript)

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn(expected_error, result.stderr)

        for replacement in (1, 2, 65_535):
            with self.subTest(bytes=replacement):
                ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
                transcript = json.loads(
                    self.fixture_transcript.read_text(encoding="utf-8")
                )
                transcript["events"][0]["bytes"] = replacement
                self.rewrite_transcript_and_bind_receipt(ledger, transcript)

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("target, SNI, or byte count mismatch", result.stderr)

    def test_encrypted_dns_transcript_rejects_self_consistent_wrong_query_host(
        self,
    ) -> None:
        for query_host in ("", "unrelated.fixture.test"):
            with self.subTest(query_host=query_host):
                gate_id = "dns-virtual-vpn-resolver"
                ledger = self.write_dns_action_inputs(gate_id)
                transcript = json.loads(
                    self.fixture_transcript.read_text(encoding="utf-8")
                )
                query_sha256 = hashlib.sha256(
                    b"ripdpi:dns-evidence-query:v1:" + query_host.encode("ascii")
                ).hexdigest()
                transcript["queryHost"] = query_host
                transcript["querySha256"] = query_sha256
                transcript["events"][0]["detail"] = query_host
                self.rewrite_transcript_and_bind_receipt(ledger, transcript)
                action_receipt = json.loads(
                    self.action_receipt.read_text(encoding="utf-8")
                )
                action_receipt["facts"]["virtualQuerySha256"] = query_sha256
                action_receipt["querySetSha256"] = receipt_contract.query_set_sha256(
                    gate_id, action_receipt["facts"]
                )
                receipt_raw = canonical(action_receipt)
                self.action_receipt.write_bytes(receipt_raw)
                ledger["semanticRules"][0]["actionReceiptSha256"] = hashlib.sha256(
                    receipt_raw
                ).hexdigest()
                self.save_ledger(ledger)

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("queryHost is not the expected target", result.stderr)

    def test_encrypted_dns_transcript_rejects_malformed_event_value_types(
        self,
    ) -> None:
        for field, replacement in (("sni", 1), ("bytes", True)):
            with self.subTest(field=field):
                ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
                transcript = json.loads(
                    self.fixture_transcript.read_text(encoding="utf-8")
                )
                transcript["events"][0][field] = replacement
                self.rewrite_transcript_and_bind_receipt(
                    ledger, transcript, refresh_inventory=False
                )

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("fixture transcript event value mismatch", result.stderr)

    def test_encrypted_dns_transcript_binds_epoch_window_and_event_order(self) -> None:
        ledger = self.write_dns_action_inputs(
            "dns-no-isp-fallback-on-encrypted-resolver-outage"
        )
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"][0]["createdAt"] = 99_999
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("timestamp is outside its window", result.stderr)

        ledger = self.write_dns_action_inputs(
            "dns-no-isp-fallback-on-encrypted-resolver-outage"
        )
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"].reverse()
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("event order mismatch", result.stderr)

    def test_encrypted_dns_observer_flow_binds_exact_transcript_peer(self) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"][0]["peer"] = "203.0.113.77:39999"
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("one transcript-bound bidirectional TCP flow", result.stderr)

    def test_encrypted_dns_role_mapping_rejects_tls_payload_drift(self) -> None:
        query_host = (
            f"virtual-vpn-resolver-{CORRELATION_ID[:16]}.fixture.test"
        )
        self.write_dns_action_inputs(
            "dns-virtual-vpn-resolver",
            observer_outbound_tls=(
                tls_record(22, b"test")
                + tls_record(23, b"x" * (dns_query_wire_size(query_host) + 2))
            ),
        )

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("TLS flow mapping mismatch", result.stderr)

    def test_encrypted_dns_role_mapping_rejects_retimed_copy_with_padding(
        self,
    ) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"][0]["peer"] = "192.0.2.10:20001"
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)
        copied = retime_pcap(self.client_pcap.read_bytes(), 1)
        self.observer_pcap.write_bytes(add_observer_control(copied))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("reuse one copied transcript-bound", result.stderr)

    def test_encrypted_dns_rejects_retimed_padded_copy_after_tuple_rewrite(
        self,
    ) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        transcript["events"][0]["peer"] = "203.0.113.88:40001"
        self.rewrite_transcript_and_bind_receipt(ledger, transcript)
        copied = rewrite_ethernet_ipv4_tcp_flow_source(
            retime_pcap(self.client_pcap.read_bytes(), 1),
            old_address="192.0.2.10",
            old_port=20001,
            new_address="203.0.113.88",
            new_port=40001,
        )
        self.observer_pcap.write_bytes(add_observer_control(copied))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tuple-independent normalized flow", result.stderr)

    def test_encrypted_dns_rejects_extra_bytes_around_tls_records(self) -> None:
        query_host = f"virtual-vpn-resolver-{CORRELATION_ID[:16]}.fixture.test"
        handshake = tls_record(22, b"test")
        application = tls_record(23, b"q" * (dns_query_wire_size(query_host) + 2))
        mutations = {
            "before": b"x" + handshake + application,
            "between": handshake + b"x" + application,
            "after": handshake + application + b"x",
            "partial": handshake + application[:-1],
        }
        for position, payload in mutations.items():
            with self.subTest(position=position):
                ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
                raw = self.client_pcap.read_bytes()
                records = pcap_records(raw)
                frame = ethernet_ipv4_tcp(
                    payload,
                    source_port=20001,
                    destination_port=FIXTURE_DOT_PORT,
                    source_address="192.0.2.10",
                )
                records[1] = pcap_record(100, 140_000, frame)
                self.client_pcap.write_bytes(raw[:24] + b"".join(records))
                self.save_ledger(ledger)

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertRegex(result.stderr, r"TLS stream (contains|has)")

    def test_proxied_dns_rejects_unoffered_socks_auth_selection(self) -> None:
        ledger = self.write_dns_action_inputs(
            "dns-proxied-through-tunnelled-resolver"
        )
        raw = self.client_pcap.read_bytes()
        malformed = raw.replace(
            b"\x05\x01\x00\x05\x01\x00",
            b"\x05\x01\x02\x05\x01\x00",
            1,
        )
        self.assertNotEqual(raw, malformed)
        self.client_pcap.write_bytes(malformed)
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SOCKS greeting does not offer no-auth", result.stderr)

    def test_encrypted_dns_rejects_forbidden_service_in_redacted_inventory(
        self,
    ) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = json.loads(self.fixture_transcript.read_text(encoding="utf-8"))
        forbidden = {
            "service": "dns_udp",
            "protocol": "udp",
            "peer": "192.0.2.10:53000",
            "target": f"{FIXTURE_ADDRESS}:{FIXTURE_DNS_PORT}",
            "detail": transcript["queryHost"],
            "bytes": dns_query_wire_size(transcript["queryHost"]),
            "sni": None,
            "createdAt": 100_155,
        }
        transcript["eventInventory"].append(redacted_event(forbidden))
        self.rewrite_transcript_and_bind_receipt(
            ledger, transcript, refresh_inventory=False
        )

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("plaintext or alternate resolver use", result.stderr)

    def test_encrypted_dns_observer_flow_rejects_endpoint_rewrite(self) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        observer = self.observer_pcap.read_bytes().replace(
            ipaddress.ip_address("203.0.113.77").packed,
            ipaddress.ip_address("203.0.113.88").packed,
        )
        self.observer_pcap.write_bytes(observer)
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("one transcript-bound bidirectional TCP flow", result.stderr)

    def test_encrypted_dns_accepts_compatible_repacketization_and_overlap(
        self,
    ) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        outbound = b"\x16\x03\x03\x00\x04test"
        for capture, source_address, source_port, offset in (
            (self.client_pcap, "192.0.2.10", 20001, 0),
            (self.observer_pcap, "203.0.113.77", 30001, 100),
        ):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            same_sequence_prefix = ethernet_ipv4_tcp(
                outbound[:7],
                source_port=source_port,
                destination_port=FIXTURE_DOT_PORT,
                sequence=0,
                source_address=source_address,
            )
            compatible_overlap = ethernet_ipv4_tcp(
                outbound[4:],
                source_port=source_port,
                destination_port=FIXTURE_DOT_PORT,
                sequence=4,
                source_address=source_address,
            )
            records[2:2] = [
                pcap_record(100, 145_000 + offset, same_sequence_prefix),
                pcap_record(100, 150_000 + offset, compatible_overlap),
            ]
            capture.write_bytes(raw[:24] + b"".join(records))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_encrypted_dns_reassembles_tls_across_tcp_sequence_wrap(self) -> None:
        ledger = self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        query_host = (
            f"virtual-vpn-resolver-{CORRELATION_ID[:16]}.fixture.test"
        )
        outbound = tls_record(22, b"test") + tls_record(
            23, b"q" * (dns_query_wire_size(query_host) + 2)
        )
        for capture, source_address, source_port, offset in (
            (self.client_pcap, "192.0.2.10", 20001, 0),
            (self.observer_pcap, "203.0.113.77", 30001, 100),
        ):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            before_wrap = ethernet_ipv4_tcp(
                outbound[:4],
                source_port=source_port,
                destination_port=FIXTURE_DOT_PORT,
                sequence=0xFFFFFFFC,
                source_address=source_address,
            )
            after_wrap = ethernet_ipv4_tcp(
                outbound[4:],
                source_port=source_port,
                destination_port=FIXTURE_DOT_PORT,
                sequence=0,
                source_address=source_address,
            )
            records[1:2] = [
                pcap_record(100, 140_000 + offset, before_wrap),
                pcap_record(100, 145_000 + offset, after_wrap),
            ]
            capture.write_bytes(raw[:24] + b"".join(records))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_encrypted_dns_outputs_redact_transcript_and_flow_identifiers(self) -> None:
        self.write_dns_action_inputs("dns-virtual-vpn-resolver")
        transcript = self.fixture_transcript.read_bytes()
        query_host = json.loads(transcript)["queryHost"]

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            published = output.read_bytes()
            for private_value in (
                transcript,
                query_host.encode("ascii"),
                b"fixture.test",
                b"198.51.100.20",
                b"203.0.113.77",
                b"192.0.2.10",
                b"client-hello",
                b"server-hello",
            ):
                self.assertNotIn(private_value, published)

    def test_startup_rule_counts_direct_dns_inside_blocking_window(self) -> None:
        self.write_startup_inputs()
        leak = ethernet_ipv4_udp(
            dns_packet(transaction_id=0x9999),
            source_port=24000,
            destination_port=FIXTURE_DNS_PORT,
        )
        for capture in (self.client_pcap, self.observer_pcap):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            record = struct.pack("<IIII", 100, 175_000, len(leak), len(leak)) + leak
            capture.write_bytes(
                raw[:24] + b"".join(records[:3] + [record] + records[3:])
            )
        ledger = self.load_ledger()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            window = json.loads(output.read_text(encoding="utf-8"))["windows"][0]
            self.assertEqual(window["unexpectedPacketCount"], 1)

    def test_startup_rule_normalizes_exact_tcp_marker_retransmission(self) -> None:
        self.write_startup_inputs()
        for capture in (self.client_pcap, self.observer_pcap):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            capture.write_bytes(
                raw[:24] + b"".join(records[:1] + records[:1] + records[1:])
            )
        ledger = self.load_ledger()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            window = json.loads(output.read_text(encoding="utf-8"))["windows"][0]
            self.assertEqual(window["unexpectedPacketCount"], 0)
            self.assertEqual(window["captureErrorCount"], 0)

    def test_startup_rule_counts_packet_between_split_outcome_segments(self) -> None:
        self.write_startup_inputs()
        outcome = marker(STARTUP_GATE, "direct_window", "outcome")
        split = len(outcome) // 2
        for index, capture in enumerate((self.client_pcap, self.observer_pcap)):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            source_address = "192.0.2.10" if index == 0 else "203.0.113.77"
            source_port = 40000 + index * 1000
            first = ethernet_ipv4_tcp(
                outcome[:split],
                source_port=source_port,
                destination_port=FIXTURE_CONTROL_PORT,
                sequence=700,
                source_address=source_address,
            )
            leak = ethernet_ipv4_udp(
                b"forbidden-between-outcome-segments",
                source_port=source_port + 1,
                destination_port=19999,
                source_address=source_address,
            )
            second = ethernet_ipv4_tcp(
                outcome[split:],
                source_port=source_port,
                destination_port=FIXTURE_CONTROL_PORT,
                sequence=700 + split,
                source_address=source_address,
            )
            replacements = [
                struct.pack("<IIII", 100, 300_000, len(first), len(first)) + first,
                struct.pack("<IIII", 100, 310_000, len(leak), len(leak)) + leak,
                struct.pack("<IIII", 100, 320_000, len(second), len(second)) + second,
            ]
            capture.write_bytes(raw[:24] + b"".join(records[:-1] + replacements))
        ledger = self.load_ledger()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            window = json.loads(output.read_text(encoding="utf-8"))["windows"][0]
            self.assertEqual(window["unexpectedPacketCount"], 1)

    def test_startup_rule_requires_matching_post_ready_dns_on_both_vantages(
        self,
    ) -> None:
        self.write_startup_inputs()
        raw = self.observer_pcap.read_bytes()
        records = pcap_records(raw)
        self.observer_pcap.write_bytes(raw[:24] + b"".join(records[:4] + records[5:]))
        ledger = self.load_ledger()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("post-ready fixture DNS query/response", result.stderr)
        self.assertFalse(self.client_output.exists())
        self.assertFalse(self.observer_output.exists())

    def test_startup_rule_accepts_one_retained_startup_exchange(self) -> None:
        ledger = self.write_startup_inputs()
        for index, capture in enumerate((self.client_pcap, self.observer_pcap)):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            source_address = "192.0.2.10" if index == 0 else "203.0.113.77"
            source_port = 20004 if index == 0 else 30004
            query = ethernet_ipv4_udp(
                dns_packet(transaction_id=0x5678),
                source_port=source_port,
                destination_port=FIXTURE_DNS_PORT,
                source_address=source_address,
            )
            response = ethernet_ipv4_udp(
                dns_packet(response=True, transaction_id=0x5678),
                source_port=FIXTURE_DNS_PORT,
                destination_port=source_port,
                source_address=FIXTURE_ADDRESS,
                destination_address=source_address,
            )
            retained_records = [
                struct.pack("<IIII", 100, 220_000, len(query), len(query)) + query,
                struct.pack("<IIII", 100, 230_000, len(response), len(response))
                + response,
            ]
            capture.write_bytes(
                raw[:24]
                + b"".join(records[:-1] + retained_records + records[-1:])
            )

        receipt = json.loads(self.action_receipt.read_text(encoding="utf-8"))
        receipt["facts"]["postReadyDnsEventCount"] = 2
        receipt_raw = canonical(receipt)
        self.action_receipt.write_bytes(receipt_raw)
        ledger["semanticRules"][0]["actionReceiptSha256"] = hashlib.sha256(
            receipt_raw
        ).hexdigest()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            window = json.loads(output.read_text(encoding="utf-8"))["windows"][0]
            self.assertEqual(window["unexpectedPacketCount"], 0)

    def test_startup_rule_rejects_nxdomain_without_fixture_answer(self) -> None:
        self.write_startup_inputs()
        for index, capture in enumerate((self.client_pcap, self.observer_pcap)):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            destination_address = "192.0.2.10" if index == 0 else "203.0.113.77"
            destination_port = 20003 if index == 0 else 30003
            response = ethernet_ipv4_udp(
                dns_packet(response=True, rcode=3, include_answer=False),
                source_port=FIXTURE_DNS_PORT,
                destination_port=destination_port,
                source_address=FIXTURE_ADDRESS,
                destination_address=destination_address,
            )
            record = (
                struct.pack("<IIII", 100, 210_000, len(response), len(response))
                + response
            )
            capture.write_bytes(
                raw[:24] + b"".join(records[:4] + [record] + records[5:])
            )
        ledger = self.load_ledger()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("matching NOERROR", result.stderr)
        self.assertFalse(self.client_output.exists())
        self.assertFalse(self.observer_output.exists())

    def test_startup_rule_rejects_non_reversed_dns_response_tuple(self) -> None:
        self.write_startup_inputs()
        for index, capture in enumerate((self.client_pcap, self.observer_pcap)):
            raw = capture.read_bytes()
            records = pcap_records(raw)
            destination_address = "192.0.2.10" if index == 0 else "203.0.113.77"
            response = ethernet_ipv4_udp(
                dns_packet(response=True),
                source_port=FIXTURE_DNS_PORT,
                destination_port=65000,
                source_address=FIXTURE_ADDRESS,
                destination_address=destination_address,
            )
            record = (
                struct.pack("<IIII", 100, 210_000, len(response), len(response))
                + response
            )
            capture.write_bytes(
                raw[:24] + b"".join(records[:4] + [record] + records[5:])
            )
        ledger = self.load_ledger()
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("matching NOERROR", result.stderr)

    def test_startup_rule_binds_private_receipt_and_fixture_identity(self) -> None:
        ledger = self.write_startup_inputs()
        for mutation, message in (
            ("receipt", "receipt digest mismatch"),
            ("fixture", "fixture manifest identity digest mismatch"),
        ):
            with self.subTest(mutation=mutation):
                self.write_startup_inputs()
                if mutation == "receipt":
                    self.action_receipt.write_bytes(
                        self.action_receipt.read_bytes() + b" "
                    )
                else:
                    fixture = json.loads(
                        self.fixture_manifest.read_text(encoding="utf-8")
                    )
                    fixture["controlPort"] += 1
                    self.fixture_manifest.write_bytes(canonical(fixture))
                result = self.run_oracle()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)

        self.assertIsInstance(ledger, dict)

    def test_startup_rule_accepts_real_producer_shaped_fixture_manifest(self) -> None:
        self.write_startup_inputs()
        fixture = json.loads(self.fixture_manifest.read_text(encoding="utf-8"))
        self.fixture_manifest.write_text(
            json.dumps(fixture, indent=2, sort_keys=False) + "\n",
            encoding="utf-8",
        )
        os.chmod(self.fixture_manifest, 0o600)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_startup_rule_rejects_non_private_fixture_manifest(self) -> None:
        self.write_startup_inputs()
        os.chmod(self.fixture_manifest, 0o644)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("mode-0600", result.stderr)

    def test_startup_rule_rejects_symlinked_fixture_manifest(self) -> None:
        self.write_startup_inputs()
        real_manifest = self.root / "real-fixture-manifest.json"
        self.fixture_manifest.rename(real_manifest)
        self.fixture_manifest.symlink_to(real_manifest)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.client_output.exists())
        self.assertFalse(self.observer_output.exists())

    def test_private_marker_preimage_is_not_accepted_as_wire_evidence(self) -> None:
        ledger = self.write_inputs()
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(
                    marker_preimage("window-a", "direct_window", "action")
                ),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(
                    marker_preimage("window-a", "direct_window", "outcome")
                ),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exactly one action and one outcome", result.stderr)

    def test_missing_duplicate_and_cross_window_markers_fail_closed(self) -> None:
        cases = {
            "missing": [
                (
                    100,
                    100_000,
                    ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
                )
            ],
            "duplicate": [
                (
                    100,
                    100_000,
                    ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
                ),
                (
                    100,
                    150_000,
                    ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
                ),
                (
                    100,
                    200_000,
                    ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
                ),
            ],
        }
        for name, packets in cases.items():
            with self.subTest(name=name):
                ledger = self.write_inputs()
                self.client_pcap.write_bytes(pcap_bytes(packets))
                self.observer_pcap.write_bytes(pcap_bytes(packets))
                self.save_ledger(ledger)
                result = self.run_oracle()
                self.assertNotEqual(result.returncode, 0)
                if name == "missing":
                    self.assertIn("exactly one action and one outcome", result.stderr)
                else:
                    self.assertIn("duplicate action marker", result.stderr)

        ledger = self.write_inputs()
        self.add_window_b(ledger)
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-b", "dns", "action")),
            ),
            (
                100,
                300_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
            (101, 300_000, ethernet_ipv4_tcp(marker("window-b", "dns", "outcome"))),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("without overlap", result.stderr)

    def test_truncated_pcap_is_rejected_after_digest_validation(self) -> None:
        ledger = self.write_inputs()
        self.client_pcap.write_bytes(self.client_pcap.read_bytes()[:-1])
        raw = self.client_pcap.read_bytes()
        ledger["captures"]["client-underlay"]["rawCaptureSha256"] = hashlib.sha256(
            raw
        ).hexdigest()
        self.ledger_path.write_bytes(canonical(ledger))
        self.client_metadata.write_bytes(
            canonical(capture_metadata("client-underlay", raw, packet_count=2))
        )

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("truncated PCAP packet data", result.stderr)
        self.assertFalse(self.client_output.exists())
        self.assertFalse(self.observer_output.exists())

    def test_non_truncated_malformed_packet_remains_a_hard_error(self) -> None:
        ledger = self.write_inputs()
        malformed = b"\x00" * 12 + struct.pack("!H", 0x0800) + b"\x45"
        packets = [
            (100, 50_000, malformed),
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("truncated or malformed IPv4 packet", result.stderr)

    def test_capture_digest_mismatch_is_rejected_before_packet_trust(self) -> None:
        self.write_inputs()
        self.client_pcap.write_bytes(self.client_pcap.read_bytes() + b"tampered")

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("digest mismatch", result.stderr)
        self.assertFalse(self.client_output.exists())

    def test_copied_single_vantage_capture_is_rejected(self) -> None:
        ledger = self.write_inputs()
        copied = self.client_pcap.read_bytes()
        self.observer_pcap.write_bytes(copied)
        digest = hashlib.sha256(copied).hexdigest()
        ledger["captures"]["external-observer"]["rawCaptureSha256"] = digest
        self.ledger_path.write_bytes(canonical(ledger))
        self.observer_metadata.write_bytes(
            canonical(capture_metadata("external-observer", copied))
        )

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("distinct raw digests", result.stderr)

    def test_unexpected_packet_is_counted_without_accepting_caller_counters(
        self,
    ) -> None:
        ledger = self.write_inputs()
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (100, 150_000, ethernet_ipv4_tcp(b"unlisted private traffic")),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        observation = json.loads(self.client_output.read_text(encoding="utf-8"))
        self.assertEqual(observation["windows"][0]["expectedPacketCount"], 2)
        self.assertEqual(observation["windows"][0]["unexpectedPacketCount"], 1)

        ledger = self.load_ledger()
        ledger["verdict"] = "PASS"
        self.ledger_path.write_bytes(canonical(ledger))
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown fields: verdict", result.stderr)
        self.assertFalse(self.client_output.exists())
        self.assertFalse(self.observer_output.exists())

        ledger = self.write_inputs()
        ledger["expectedPacketCount"] = 999
        self.ledger_path.write_bytes(canonical(ledger))
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown fields: expectedPacketCount", result.stderr)

    def test_adjacent_windows_use_start_inclusive_finish_exclusive_boundaries(
        self,
    ) -> None:
        ledger = self.write_inputs()
        self.add_window_b(ledger)
        packets = [
            (100, 0, ethernet_ipv4_tcp(marker("window-a", "direct_window", "action"))),
            (
                100,
                999_999,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
            (101, 0, ethernet_ipv4_tcp(marker("window-b", "dns", "action"))),
            (101, 999_999, ethernet_ipv4_tcp(marker("window-b", "dns", "outcome"))),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        observation = json.loads(self.client_output.read_text(encoding="utf-8"))
        self.assertEqual(
            [window["expectedPacketCount"] for window in observation["windows"]],
            [2, 2],
        )

    def test_snaplen_truncation_is_accounted_per_window(self) -> None:
        ledger = self.write_inputs()
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (100, 150_000, ethernet_ipv4_tcp(b"x" * 400)),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets, snaplen=192))
        self.observer_pcap.write_bytes(pcap_bytes(packets, snaplen=192))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            window = json.loads(output.read_text(encoding="utf-8"))["windows"][0]
            self.assertEqual(window["captureErrorCount"], 1)
            self.assertEqual(window["expectedPacketCount"], 2)

    def test_packets_before_and_after_plan_windows_are_ignored(self) -> None:
        ledger = self.write_inputs()
        packets = [
            (99, 500_000, ethernet_ipv4_tcp(b"private pre-window traffic")),
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
            (101, 500_000, ethernet_ipv4_tcp(b"private post-window traffic")),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        window = json.loads(self.client_output.read_text(encoding="utf-8"))["windows"][
            0
        ]
        self.assertEqual(window["unexpectedPacketCount"], 0)

    def test_remote_fractional_timestamps_are_aligned_only_by_markers(self) -> None:
        ledger = self.write_inputs()
        packets = [
            (1_000_000, 123_456, ethernet_ipv4_tcp(b"remote pre-window")),
            (
                1_000_001,
                111_111,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                1_000_001,
                999_999,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
            (1_000_002, 654_321, ethernet_ipv4_tcp(b"remote post-window")),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        observation = json.loads(self.client_output.read_text(encoding="utf-8"))
        self.assertEqual(observation["captureStartedAtEpoch"], 99)
        self.assertEqual(observation["captureFinishedAtEpoch"], 103)
        self.assertEqual(observation["windows"][0]["startedAtEpoch"], 100)
        self.assertEqual(observation["windows"][0]["finishedAtEpoch"], 101)

    def test_source_capture_linktypes_raw_sll_and_sll2(self) -> None:
        fixtures = {
            "raw": (101, lambda payload: raw_ipv4_tcp(payload)),
            "sll": (
                113,
                lambda payload: linux_sll(0x0800, raw_ipv4_tcp(payload)),
            ),
            "sll2": (
                276,
                lambda payload: linux_sll2(0x0800, raw_ipv4_tcp(payload)),
            ),
        }
        for name, (linktype, wrap) in fixtures.items():
            with self.subTest(name=name):
                ledger = self.write_inputs()
                packets = [
                    (
                        100,
                        100_000,
                        wrap(marker("window-a", "direct_window", "action")),
                    ),
                    (
                        100,
                        200_000,
                        wrap(marker("window-a", "direct_window", "outcome")),
                    ),
                ]
                self.client_pcap.write_bytes(pcap_bytes(packets, linktype=linktype))
                self.observer_pcap.write_bytes(pcap_bytes(packets, linktype=linktype))
                self.save_ledger(ledger)

                result = self.run_oracle()

                self.assertEqual(result.returncode, 0, result.stderr)

        ledger = self.write_inputs()
        raw = self.client_pcap.read_bytes()
        unknown_linktype = raw[:20] + struct.pack("<I", 999) + raw[24:]
        self.client_pcap.write_bytes(unknown_linktype)
        self.observer_pcap.write_bytes(unknown_linktype)
        self.save_ledger(ledger)
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unsupported PCAP linktype", result.stderr)

    def test_classic_pcap_byte_orders_and_timestamp_resolutions(self) -> None:
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        for endian in ("<", ">"):
            for nanosecond in (False, True):
                with self.subTest(endian=endian, nanosecond=nanosecond):
                    ledger = self.write_inputs()
                    raw = pcap_bytes(
                        packets,
                        endian=endian,
                        nanosecond=nanosecond,
                    )
                    self.client_pcap.write_bytes(raw)
                    self.observer_pcap.write_bytes(add_observer_control(raw))
                    self.save_ledger(ledger)

                    result = self.run_oracle()

                    self.assertEqual(result.returncode, 0, result.stderr)

    def test_vlan_tagged_ethernet_sll_and_sll2(self) -> None:
        for tags in ((0x8100,), (0x88A8, 0x8100)):
            for linktype in (1, 113, 276):
                with self.subTest(tags=tags, linktype=linktype):
                    ledger = self.write_inputs()

                    def wrap(payload: bytes) -> bytes:
                        protocol, packet = vlan_stack(
                            0x0800,
                            raw_ipv4_tcp(payload),
                            tag_protocols=tags,
                        )
                        if linktype == 1:
                            return b"\x00" * 12 + struct.pack("!H", protocol) + packet
                        if linktype == 113:
                            return linux_sll(protocol, packet)
                        return linux_sll2(protocol, packet)

                    packets = [
                        (
                            100,
                            100_000,
                            wrap(marker("window-a", "direct_window", "action")),
                        ),
                        (
                            100,
                            200_000,
                            wrap(marker("window-a", "direct_window", "outcome")),
                        ),
                    ]
                    self.client_pcap.write_bytes(pcap_bytes(packets, linktype=linktype))
                    self.observer_pcap.write_bytes(
                        pcap_bytes(packets, linktype=linktype)
                    )
                    self.save_ledger(ledger)

                    result = self.run_oracle()

                    self.assertEqual(result.returncode, 0, result.stderr)

        ledger = self.write_inputs()
        protocol, packet = vlan_stack(
            0x0800,
            raw_ipv4_tcp(marker("window-a", "direct_window", "action")),
            tag_protocols=(0x88A8, 0x8100, 0x8100),
        )
        packets = [(100, 100_000, b"\x00" * 12 + struct.pack("!H", protocol) + packet)]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("excessive VLAN tag stack", result.stderr)

    def test_action_marker_must_precede_outcome_marker(self) -> None:
        ledger = self.write_inputs()
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("action marker must precede outcome marker", result.stderr)

        packets = [
            (
                100,
                300_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("action marker must precede outcome marker", result.stderr)

    def test_equal_microsecond_timestamps_use_record_order(self) -> None:
        ledger = self.write_inputs()
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_metadata_must_bind_actual_capture_and_enclose_plan(self) -> None:
        self.write_inputs()
        metadata = json.loads(self.client_metadata.read_text(encoding="utf-8"))
        cases = {
            "role": ("role", "external-observer", "role mismatch"),
            "correlation": ("correlationId", "f" * 64, "correlationId mismatch"),
            "digest": (
                "rawCaptureSha256",
                "f" * 64,
                "metadata digest does not match ledger",
            ),
            "bounds": (
                "captureStartedAtEpoch",
                101,
                "do not enclose every plan window",
            ),
            "packet-count": ("packetCount", 999, "parsed packet count does not match"),
        }
        for name, (field, value, message) in cases.items():
            with self.subTest(name=name):
                changed = dict(metadata)
                changed[field] = value
                self.client_metadata.write_bytes(canonical(changed))
                result = self.run_oracle()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.client_metadata.write_bytes(canonical(metadata))

    def test_same_inode_captures_are_rejected_as_vantage_aliases(self) -> None:
        self.write_inputs()
        self.observer_pcap.unlink()
        os.link(self.client_pcap, self.observer_pcap)
        self.write_metadata()

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must not alias the same inode", result.stderr)

    def test_arbitrary_window_kind_is_rejected(self) -> None:
        ledger = self.write_inputs()
        ledger["scenarioPlan"]["windows"][0]["kind"] = "arbitrary"
        self.ledger_path.write_bytes(canonical(ledger))

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("kind is not allowed", result.stderr)

    def test_resource_bounds_fail_before_unbounded_reads_or_scans(self) -> None:
        self.write_inputs()
        with self.ledger_path.open("wb") as handle:
            handle.truncate(1024 * 1024 + 1)
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("action ledger exceeds the size bound", result.stderr)

        ledger = self.write_inputs()
        ledger["scenarioPlan"]["windows"] *= 65
        self.ledger_path.write_bytes(canonical(ledger))
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("window count bound", result.stderr)

        ledger = self.write_inputs()
        with self.client_pcap.open("wb") as handle:
            handle.truncate(64 * 1024 * 1024 + 1)
        fake_digest = "a" * 64
        ledger["captures"]["client-underlay"]["rawCaptureSha256"] = fake_digest
        self.ledger_path.write_bytes(canonical(ledger))
        metadata = capture_metadata("client-underlay", b"", packet_count=0)
        metadata["rawCaptureSha256"] = fake_digest
        self.client_metadata.write_bytes(canonical(metadata))
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exceeds the capture size bound", result.stderr)

    def test_output_symlinks_and_hardlinks_are_rejected_without_removal(self) -> None:
        self.write_inputs()
        protected = self.root / "protected.json"
        protected.write_bytes(b"private")
        os.chmod(protected, 0o600)
        self.client_output.symlink_to(protected)

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.client_output.is_symlink())
        self.assertEqual(protected.read_bytes(), b"private")

        self.client_output.unlink()
        os.link(protected, self.client_output)
        result = self.run_oracle()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("must not be hard-linked", result.stderr)
        self.assertEqual(protected.read_bytes(), b"private")

    def test_raw_identifiers_and_payload_are_never_published(self) -> None:
        ledger = self.write_inputs()
        secret = b"203.0.113.77 secret-qname.private payload-secret"
        packets = [
            (
                100,
                100_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "action")),
            ),
            (100, 150_000, ethernet_ipv4_tcp(secret)),
            (
                100,
                200_000,
                ethernet_ipv4_tcp(marker("window-a", "direct_window", "outcome")),
            ),
        ]
        self.client_pcap.write_bytes(pcap_bytes(packets))
        self.observer_pcap.write_bytes(pcap_bytes(packets))
        self.save_ledger(ledger)

        result = self.run_oracle()

        self.assertEqual(result.returncode, 0, result.stderr)
        for output in (self.client_output, self.observer_output):
            published = output.read_bytes()
            for raw_identifier in (
                b"203.0.113.77",
                b"secret-qname.private",
                b"payload-secret",
            ):
                self.assertNotIn(raw_identifier, published)

    def test_gate_specific_semantics_fail_closed_without_a_source_owned_seam(
        self,
    ) -> None:
        ledger = self.write_inputs()
        ledger["semanticRules"][0]["rule"] = "dns-query-blocked"
        self.ledger_path.write_bytes(canonical(ledger))

        result = self.run_oracle()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not prove a source-owned gate semantic seam", result.stderr)

    def test_generic_marker_pair_is_forbidden_for_every_policy_dual_vantage_gate(
        self,
    ) -> None:
        policy = json.loads(
            (ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json").read_text(
                encoding="utf-8"
            )
        )
        scope = "android-client-release"
        gate_ids = [
            gate["id"]
            for gate in policy["gates"]
            if gate.get("evidenceSources", {}).get(scope, gate.get("evidenceSource"))
            == "dual-vantage-network-manifest"
            and scope in gate.get("appliesTo", policy["appliesTo"])
        ]
        self.assertTrue(gate_ids)
        for gate_id in gate_ids:
            with self.subTest(gate_id=gate_id):
                ledger = self.write_inputs()
                window = ledger["scenarioPlan"]["windows"][0]
                window["id"] = gate_id
                window["actionMarkerSha256"] = hashlib.sha256(
                    marker_preimage(gate_id, "direct_window", "action")
                ).hexdigest()
                window["outcomeMarkerSha256"] = hashlib.sha256(
                    marker_preimage(gate_id, "direct_window", "outcome")
                ).hexdigest()
                ledger["semanticRules"][0]["windowId"] = gate_id
                self.ledger_path.write_bytes(canonical(ledger))

                result = self.run_oracle()

                self.assertNotEqual(result.returncode, 0)
                self.assertIn("generic-marker-pair is forbidden", result.stderr)
                self.assertIn(gate_id, result.stderr)

    def test_repeated_runs_are_byte_deterministic_and_reset_output_modes(self) -> None:
        self.write_inputs()
        first = self.run_oracle()
        self.assertEqual(first.returncode, 0, first.stderr)
        expected = (self.client_output.read_bytes(), self.observer_output.read_bytes())
        self.client_output.write_bytes(b"partial stale client output")
        self.observer_output.write_bytes(b"partial stale observer output")
        os.chmod(self.client_output, 0o644)
        os.chmod(self.observer_output, 0o666)

        second = self.run_oracle()

        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(
            (self.client_output.read_bytes(), self.observer_output.read_bytes()),
            expected,
        )
        self.assertEqual(os.stat(self.client_output).st_mode & 0o777, 0o600)
        self.assertEqual(os.stat(self.observer_output).st_mode & 0o777, 0o600)

        os.chmod(self.client_output, 0o644)
        os.chmod(self.observer_output, 0o666)
        self.client_output.write_bytes(b"partial stale client output")
        self.observer_output.write_bytes(b"partial stale observer output")
        self.ledger_path.write_bytes(b"not-json\n")
        failed = self.run_oracle()
        self.assertNotEqual(failed.returncode, 0)
        self.assertFalse(self.client_output.exists())
        self.assertFalse(self.observer_output.exists())


if __name__ == "__main__":
    unittest.main()
