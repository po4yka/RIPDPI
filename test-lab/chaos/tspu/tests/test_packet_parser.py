"""Unit tests for runner.packet_parser."""

from __future__ import annotations

import os
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from runner import packet_parser  # noqa: E402
from tests._helpers import build_clienthello_with_sni as _build_clienthello_with_sni  # noqa: E402


class ExtractTlsSniTests(unittest.TestCase):
    def test_extracts_sni_from_clienthello(self):
        payload = _build_clienthello_with_sni("blocked.example")
        self.assertEqual(packet_parser.extract_tls_sni(payload), "blocked.example")

    def test_returns_none_on_too_short_payload(self):
        self.assertIsNone(packet_parser.extract_tls_sni(b""))
        self.assertIsNone(packet_parser.extract_tls_sni(b"\x16\x03"))

    def test_returns_none_when_not_handshake_record(self):
        self.assertIsNone(packet_parser.extract_tls_sni(b"\x17\x03\x01\x00\x05" + b"hello"))

    def test_returns_none_when_truncated_after_record_header(self):
        self.assertIsNone(packet_parser.extract_tls_sni(b"\x16\x03\x01\x00\x05\x01\x00"))


class ParseQuicLongHeaderTests(unittest.TestCase):
    def test_initial_packet_type(self):
        is_long, pt = packet_parser.parse_quic_long_header(bytes([0xC0]))
        self.assertTrue(is_long)
        self.assertEqual(pt, "initial")

    def test_handshake_packet_type(self):
        is_long, pt = packet_parser.parse_quic_long_header(bytes([0xE0]))
        self.assertTrue(is_long)
        self.assertEqual(pt, "handshake")

    def test_short_header_returns_false(self):
        is_long, pt = packet_parser.parse_quic_long_header(bytes([0x40]))
        self.assertFalse(is_long)
        self.assertIsNone(pt)

    def test_empty_payload_returns_false(self):
        is_long, pt = packet_parser.parse_quic_long_header(b"")
        self.assertFalse(is_long)
        self.assertIsNone(pt)


class ParseOutboundPacketTests(unittest.TestCase):
    def test_tcp_with_clienthello_populates_sni(self):
        payload = _build_clienthello_with_sni("blocked.example")
        pkt = packet_parser.parse_outbound_packet(
            transport="tcp", payload=payload, src_port=49152, dst_port=443
        )
        self.assertEqual(pkt["direction"], "outbound")
        self.assertEqual(pkt["transport"], "tcp")
        self.assertEqual(pkt["sni"], "blocked.example")
        self.assertEqual(pkt["dst_port"], 443)
        self.assertEqual(pkt["tls_record_type"], "clienthello")

    def test_tcp_without_clienthello_carries_none_sni(self):
        pkt = packet_parser.parse_outbound_packet(
            transport="tcp", payload=b"\x17\x03\x01\x00\x05data!", src_port=49152, dst_port=443
        )
        self.assertIsNone(pkt["sni"])
        self.assertIsNone(pkt["tls_record_type"])

    def test_udp_initial_populates_quic_fields(self):
        pkt = packet_parser.parse_outbound_packet(
            transport="udp", payload=bytes([0xC0]) + bytes(10), src_port=49152, dst_port=443
        )
        self.assertTrue(pkt["quic_long_header"])
        self.assertEqual(pkt["quic_packet_type"], "initial")
        self.assertIsNone(pkt["sni"])
        self.assertIsNone(pkt["alpn"])

    def test_udp_short_header_is_not_long(self):
        pkt = packet_parser.parse_outbound_packet(
            transport="udp", payload=bytes([0x40]) + bytes(10), src_port=49152, dst_port=443
        )
        self.assertFalse(pkt["quic_long_header"])
        self.assertIsNone(pkt["quic_packet_type"])


if __name__ == "__main__":
    unittest.main()
