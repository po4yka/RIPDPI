"""Unit tests for the v1.1 pattern classifiers added on top of v1."""

from __future__ import annotations

import os
import sys
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from patterns import ip_blackhole_after_n_bytes as ip_blackhole, mtu_clamp, sni_replace  # noqa: E402


class SniReplaceTests(unittest.TestCase):
    def _config(self):
        return {"sni_blocklist": ["blocked.example"], "replacement_sni": "sinkhole.local"}

    def test_match_when_sni_on_blocklist(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "sni": "blocked.example", "payload_hex": "16"}
            ]
        }
        result = sni_replace.classify(trace, self._config())
        self.assertTrue(result["matched"])
        self.assertEqual(result["matched_sni"], "blocked.example")
        self.assertEqual(result["evidence"]["would_replace_with"], "sinkhole.local")

    def test_no_match_when_sni_absent(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "sni": None, "payload_hex": "16"}
            ]
        }
        result = sni_replace.classify(trace, self._config())
        self.assertFalse(result["matched"])

    def test_no_match_on_udp(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "udp", "sni": "blocked.example", "payload_hex": "c0"}
            ]
        }
        result = sni_replace.classify(trace, self._config())
        self.assertFalse(result["matched"])


class IpBlackholeTests(unittest.TestCase):
    def test_threshold_zero_never_matches(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "dst_port": 443, "payload_hex": "ff" * 5000}
            ]
        }
        result = ip_blackhole.classify(trace, {"threshold_bytes": 0})
        self.assertFalse(result["matched"])

    def test_matches_when_cumulative_exceeds_threshold(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "dst_port": 443, "payload_hex": "ff" * 600},
                {"direction": "outbound", "transport": "tcp", "dst_port": 443, "payload_hex": "ff" * 500},
            ]
        }
        result = ip_blackhole.classify(
            trace, {"threshold_bytes": 1000, "target_dst_ports": [443]}
        )
        self.assertTrue(result["matched"])
        self.assertEqual(result["matched_packet_index"], 1)
        self.assertGreaterEqual(result["evidence"]["cumulative_bytes"], 1000)

    def test_port_filter_excludes_off_target_packets(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "dst_port": 80, "payload_hex": "ff" * 5000}
            ]
        }
        result = ip_blackhole.classify(
            trace, {"threshold_bytes": 1000, "target_dst_ports": [443]}
        )
        self.assertFalse(result["matched"])

    def test_inbound_not_counted(self):
        trace = {
            "packets": [
                {"direction": "inbound", "transport": "tcp", "dst_port": 443, "payload_hex": "ff" * 5000}
            ]
        }
        result = ip_blackhole.classify(
            trace, {"threshold_bytes": 1000, "target_dst_ports": [443]}
        )
        self.assertFalse(result["matched"])


class MtuClampTests(unittest.TestCase):
    def test_threshold_zero_never_matches(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "payload_hex": "ff" * 5000}
            ]
        }
        result = mtu_clamp.classify(trace, {"mtu_payload_bytes": 0})
        self.assertFalse(result["matched"])

    def test_matches_when_payload_exceeds_threshold(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "payload_hex": "ff" * 201}
            ]
        }
        result = mtu_clamp.classify(trace, {"mtu_payload_bytes": 200})
        self.assertTrue(result["matched"])
        self.assertEqual(result["matched_packet_index"], 0)
        self.assertEqual(result["evidence"]["payload_bytes_len"], 201)

    def test_no_match_below_threshold(self):
        trace = {
            "packets": [
                {"direction": "outbound", "transport": "tcp", "payload_hex": "ff" * 199}
            ]
        }
        result = mtu_clamp.classify(trace, {"mtu_payload_bytes": 200})
        self.assertFalse(result["matched"])

    def test_inbound_skipped(self):
        trace = {
            "packets": [
                {"direction": "inbound", "transport": "tcp", "payload_hex": "ff" * 5000}
            ]
        }
        result = mtu_clamp.classify(trace, {"mtu_payload_bytes": 200})
        self.assertFalse(result["matched"])


if __name__ == "__main__":
    unittest.main()
