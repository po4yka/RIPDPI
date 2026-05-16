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


def _build_clienthello_with_sni(sni: str) -> bytes:
    """Build a minimal TLS ClientHello with the given SNI extension."""
    sni_bytes = sni.encode("ascii")
    # server_name extension body: list_len(2) name_type(1) name_len(2) name
    name_entry = bytes([0x00]) + len(sni_bytes).to_bytes(2, "big") + sni_bytes
    sni_ext_body = len(name_entry).to_bytes(2, "big") + name_entry
    sni_ext = bytes([0x00, 0x00]) + len(sni_ext_body).to_bytes(2, "big") + sni_ext_body

    extensions = sni_ext
    ext_block = len(extensions).to_bytes(2, "big") + extensions

    # ClientHello body: legacy_version(2) random(32) session_id_len(1)
    # cipher_suites_len(2) cipher_suites(2) compression_methods_len(1)
    # compression_methods(1) extensions_block
    ch_body = (
        bytes([0x03, 0x03])
        + bytes(32)
        + bytes([0x00])
        + bytes([0x00, 0x02])
        + bytes([0x00, 0x35])
        + bytes([0x01])
        + bytes([0x00])
        + ext_block
    )
    # Handshake header: type(1) length(3)
    handshake = bytes([0x01]) + len(ch_body).to_bytes(3, "big") + ch_body
    # Record header: type(1) version(2) length(2)
    record = bytes([0x16, 0x03, 0x01]) + len(handshake).to_bytes(2, "big") + handshake
    return record


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
