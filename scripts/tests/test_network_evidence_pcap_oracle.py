from __future__ import annotations

import hashlib
import ipaddress
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ORACLE = ROOT / "test-lab/scripts/network-evidence-pcap-oracle.py"
CORRELATION_ID = "c" * 64
SOURCE_SHA = "d" * 40
CLIENT_ARTIFACT_SHA256 = "e" * 64
TEST_ARTIFACT_SHA256 = "f" * 64
WINDOW_A = "oracle-fixture-window-a"
WINDOW_B = "oracle-fixture-window-b"


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


def ethernet_ipv4_tcp(payload: bytes) -> bytes:
    tcp = struct.pack("!HHIIHHHH", 12345, 443, 0, 0, 5 << 12, 0, 0, 0) + payload
    source = ipaddress.ip_address("192.0.2.10").packed
    destination = ipaddress.ip_address("198.51.100.20").packed
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


def ethernet_ipv6_udp(payload: bytes) -> bytes:
    udp = struct.pack("!HHHH", 12345, 443, 8 + len(payload), 0) + payload
    source = ipaddress.ip_address("2001:db8::10").packed
    destination = ipaddress.ip_address("2001:db8::20").packed
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


class NetworkEvidencePcapOracleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.client_pcap = self.root / "client.pcap"
        self.observer_pcap = self.root / "observer.pcap"
        self.client_metadata = self.root / "client-metadata.json"
        self.observer_metadata = self.root / "observer-metadata.json"
        self.ledger_path = self.root / "ledger.json"
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
            "version": "network_evidence_action_ledger_v1",
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

    def run_oracle(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
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
                "--client-output",
                str(self.client_output),
                "--observer-output",
                str(self.observer_output),
            ],
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
