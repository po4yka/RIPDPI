"""Unit tests for runner.classifier verdict mapping and pattern classifiers."""

from __future__ import annotations

import io
import json
import os
import struct
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from patterns import quic_initial_drop, rst_after_sni_match  # noqa: E402
from runner import classifier, pcap_writer, schema  # noqa: E402


class VerdictForTests(unittest.TestCase):
    def test_matched_false_yields_bypassed(self):
        result = {"matched": False}
        self.assertEqual(classifier.verdict_for(result, {}), schema.VERDICT_BYPASSED)

    def test_matched_true_yields_blocked(self):
        result = {"matched": True}
        self.assertEqual(classifier.verdict_for(result, {}), schema.VERDICT_BLOCKED)

    def test_force_degraded_overrides_blocked(self):
        result = {"matched": True}
        trace = {"force_degraded": True}
        self.assertEqual(classifier.verdict_for(result, trace), schema.VERDICT_DEGRADED)

    def test_missing_matched_yields_inconclusive(self):
        result = {}
        self.assertEqual(classifier.verdict_for(result, {}), schema.VERDICT_INCONCLUSIVE)


class RstAfterSniMatchTests(unittest.TestCase):
    def _config(self):
        return {"sni_blocklist": ["blocked.example"]}

    def test_match_when_sni_on_blocklist(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "sni": "blocked.example", "payload_hex": "16"}
            ]
        }
        result = rst_after_sni_match.classify(trace, self._config())
        self.assertTrue(result["matched"])
        self.assertEqual(result["matched_packet_index"], 0)
        self.assertEqual(result["matched_sni"], "blocked.example")

    def test_no_match_when_sni_absent(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "sni": None, "payload_hex": "16"}
            ]
        }
        result = rst_after_sni_match.classify(trace, self._config())
        self.assertFalse(result["matched"])

    def test_no_match_on_udp_trace(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "udp", "sni": "blocked.example", "payload_hex": "c0"}
            ]
        }
        result = rst_after_sni_match.classify(trace, self._config())
        self.assertFalse(result["matched"])

    def test_blocklist_is_case_insensitive(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "sni": "BLOCKED.EXAMPLE", "payload_hex": "16"}
            ]
        }
        result = rst_after_sni_match.classify(trace, self._config())
        self.assertTrue(result["matched"])

    def test_inbound_packets_skipped(self):
        trace = {
            "packets": [
                {"direction": "inbound", "transport": "tcp", "sni": "blocked.example", "payload_hex": "16"}
            ]
        }
        result = rst_after_sni_match.classify(trace, self._config())
        self.assertFalse(result["matched"])


class QuicInitialDropTests(unittest.TestCase):
    def _config(self):
        return {"sni_blocklist": ["blocked.example"], "alpn_blocklist": ["h3"]}

    def test_match_on_initial_with_blocked_sni(self):
        trace = {
            "packets": [
                {
                    "direction": "outbound",
                    "transport": "udp",
                    "quic_long_header": True,
                    "quic_packet_type": "initial",
                    "sni": "blocked.example",
                    "alpn": "h3",
                    "payload_hex": "c0",
                }
            ]
        }
        result = quic_initial_drop.classify(trace, self._config())
        self.assertTrue(result["matched"])
        self.assertEqual(result["matched_sni"], "blocked.example")

    def test_no_match_on_handshake_not_initial(self):
        trace = {
            "packets": [
                {
                    "direction": "outbound",
                    "transport": "udp",
                    "quic_long_header": True,
                    "quic_packet_type": "handshake",
                    "sni": "blocked.example",
                    "alpn": "h3",
                    "payload_hex": "e0",
                }
            ]
        }
        result = quic_initial_drop.classify(trace, self._config())
        self.assertFalse(result["matched"])

    def test_no_match_on_initial_with_benign_sni(self):
        trace = {
            "packets": [
                {
                    "direction": "outbound",
                    "transport": "udp",
                    "quic_long_header": True,
                    "quic_packet_type": "initial",
                    "sni": "decoy.example",
                    "alpn": None,
                    "payload_hex": "c0",
                }
            ]
        }
        result = quic_initial_drop.classify(trace, self._config())
        self.assertFalse(result["matched"])

    def test_no_match_on_tcp(self):
        trace = {
            "packets": [
                {
                    "direction": "outbound",
                    "transport": "tcp",
                    "quic_long_header": True,
                    "quic_packet_type": "initial",
                    "sni": "blocked.example",
                    "alpn": "h3",
                    "payload_hex": "c0",
                }
            ]
        }
        result = quic_initial_drop.classify(trace, self._config())
        self.assertFalse(result["matched"])


class PcapWriterTests(unittest.TestCase):
    def test_writes_valid_global_header(self):
        buf = io.BytesIO()
        count = pcap_writer.write_pcap(buf, [])
        self.assertEqual(count, 0)
        data = buf.getvalue()
        self.assertEqual(len(data), 24)
        magic, version_major, version_minor, _thiszone, _sigfigs, snaplen, linktype = struct.unpack(
            "!IHHIIII", data
        )
        self.assertEqual(magic, pcap_writer.PCAP_MAGIC)
        self.assertEqual(version_major, 2)
        self.assertEqual(version_minor, 4)
        self.assertEqual(snaplen, pcap_writer.SNAPLEN)
        self.assertEqual(linktype, pcap_writer.LINKTYPE_EN10MB)

    def test_writes_tcp_record(self):
        buf = io.BytesIO()
        packets = [
            {"transport": "tcp", "src_port": 49152, "dst_port": 443, "payload_hex": "deadbeef"}
        ]
        count = pcap_writer.write_pcap(buf, packets)
        self.assertEqual(count, 1)
        data = buf.getvalue()
        # 24-byte global header + 16-byte record header + frame.
        self.assertGreater(len(data), 24 + 16)


if __name__ == "__main__":
    unittest.main()
