"""End-to-end replay test: run the full matrix against the checked-in
fixtures and assert the expected verdicts."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest


HERE = os.path.dirname(os.path.abspath(__file__))
TSPU_DIR = os.path.dirname(HERE)
if TSPU_DIR not in sys.path:
    sys.path.insert(0, TSPU_DIR)


from runner import replay  # noqa: E402


def _load_matrix():
    with open(os.path.join(TSPU_DIR, "matrix.json"), "r", encoding="utf-8") as fh:
        return json.load(fh)


class ReplayMatrixTests(unittest.TestCase):
    # Pre-computed (pattern_id, desync_mode_id) -> expected verdict.
    EXPECTED = {
        # rst-after-sni-match
        ("rst-after-sni-match", "split_offset_3_chlo"): "blocked",
        ("rst-after-sni-match", "tlsrandrec_profile_a"): "bypassed",
        ("rst-after-sni-match", "fakettl_2_then_real"): "blocked",
        ("rst-after-sni-match", "quic_initial_with_blocked_sni"): "bypassed",
        ("rst-after-sni-match", "quic_initial_with_fake_decoy"): "bypassed",
        ("rst-after-sni-match", "large_tls_record_single_packet"): "blocked",
        ("rst-after-sni-match", "long_lived_flow_post_blackhole"): "bypassed",
        # quic-initial-drop
        ("quic-initial-drop", "split_offset_3_chlo"): "bypassed",
        ("quic-initial-drop", "tlsrandrec_profile_a"): "bypassed",
        ("quic-initial-drop", "fakettl_2_then_real"): "bypassed",
        ("quic-initial-drop", "quic_initial_with_blocked_sni"): "blocked",
        ("quic-initial-drop", "quic_initial_with_fake_decoy"): "bypassed",
        ("quic-initial-drop", "large_tls_record_single_packet"): "bypassed",
        ("quic-initial-drop", "long_lived_flow_post_blackhole"): "bypassed",
        # sni-replace
        ("sni-replace", "split_offset_3_chlo"): "blocked",
        ("sni-replace", "tlsrandrec_profile_a"): "bypassed",
        ("sni-replace", "fakettl_2_then_real"): "blocked",
        ("sni-replace", "quic_initial_with_blocked_sni"): "bypassed",
        ("sni-replace", "quic_initial_with_fake_decoy"): "bypassed",
        ("sni-replace", "large_tls_record_single_packet"): "blocked",
        ("sni-replace", "long_lived_flow_post_blackhole"): "bypassed",
        # ip-blackhole-after-n-bytes (threshold 1000, port 443 only)
        ("ip-blackhole-after-n-bytes", "split_offset_3_chlo"): "bypassed",
        ("ip-blackhole-after-n-bytes", "tlsrandrec_profile_a"): "bypassed",
        ("ip-blackhole-after-n-bytes", "fakettl_2_then_real"): "bypassed",
        ("ip-blackhole-after-n-bytes", "quic_initial_with_blocked_sni"): "bypassed",
        ("ip-blackhole-after-n-bytes", "quic_initial_with_fake_decoy"): "bypassed",
        ("ip-blackhole-after-n-bytes", "large_tls_record_single_packet"): "bypassed",
        ("ip-blackhole-after-n-bytes", "long_lived_flow_post_blackhole"): "blocked",
        # mtu-clamp (200 bytes)
        ("mtu-clamp", "split_offset_3_chlo"): "bypassed",
        ("mtu-clamp", "tlsrandrec_profile_a"): "bypassed",
        ("mtu-clamp", "fakettl_2_then_real"): "bypassed",
        ("mtu-clamp", "quic_initial_with_blocked_sni"): "bypassed",
        ("mtu-clamp", "quic_initial_with_fake_decoy"): "bypassed",
        ("mtu-clamp", "large_tls_record_single_packet"): "blocked",
        ("mtu-clamp", "long_lived_flow_post_blackhole"): "bypassed",
    }

    def test_full_matrix_verdicts_match_expectations(self):
        matrix = _load_matrix()
        fixtures_dir = os.path.join(TSPU_DIR, "fixtures")
        with tempfile.TemporaryDirectory() as out_dir:
            report = replay.replay_matrix(matrix, fixtures_dir, out_dir)
            seen = set()
            for cell in report["cells"]:
                key = (cell["pattern_id"], cell["desync_mode_id"])
                seen.add(key)
                self.assertIn(key, self.EXPECTED, f"unexpected cell {key}")
                self.assertEqual(
                    cell["verdict"],
                    self.EXPECTED[key],
                    f"verdict mismatch for cell {key}: got {cell['verdict']}",
                )
            self.assertEqual(seen, set(self.EXPECTED.keys()), "matrix missing expected cells")
            for cell in report["cells"]:
                pcap_rel = cell["evidence"]["pcap_path"]
                pcap_path = os.path.join(out_dir, pcap_rel)
                self.assertTrue(os.path.exists(pcap_path), f"missing pcap {pcap_path}")
                self.assertGreater(os.path.getsize(pcap_path), 24)

    def test_totals_sum_to_cell_count(self):
        matrix = _load_matrix()
        fixtures_dir = os.path.join(TSPU_DIR, "fixtures")
        with tempfile.TemporaryDirectory() as out_dir:
            report = replay.replay_matrix(matrix, fixtures_dir, out_dir)
            total = sum(report["totals"].values())
            self.assertEqual(total, len(report["cells"]))


if __name__ == "__main__":
    unittest.main()
