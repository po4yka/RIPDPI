"""End-to-end replay test: run the full matrix (patterns + combinations)
against the checked-in fixtures and assert the expected verdicts."""

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


# Per-pattern verdicts -- the same table the per-pattern cells assert.
_PATTERN_TABLE: dict[tuple[str, str], str] = {
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

# Combination verdicts = OR over per-pattern verdicts in the combo.
_COMBINATION_TABLE: dict[tuple[str, str], str] = {
    # tcp-sni-and-mtu = rst + sni-replace + mtu-clamp
    ("combo:tcp-sni-and-mtu", "split_offset_3_chlo"): "blocked",
    ("combo:tcp-sni-and-mtu", "tlsrandrec_profile_a"): "bypassed",
    ("combo:tcp-sni-and-mtu", "fakettl_2_then_real"): "blocked",
    ("combo:tcp-sni-and-mtu", "quic_initial_with_blocked_sni"): "bypassed",
    ("combo:tcp-sni-and-mtu", "quic_initial_with_fake_decoy"): "bypassed",
    ("combo:tcp-sni-and-mtu", "large_tls_record_single_packet"): "blocked",
    ("combo:tcp-sni-and-mtu", "long_lived_flow_post_blackhole"): "bypassed",
    # quic-strict = quic-initial-drop + ip-blackhole
    ("combo:quic-strict", "split_offset_3_chlo"): "bypassed",
    ("combo:quic-strict", "tlsrandrec_profile_a"): "bypassed",
    ("combo:quic-strict", "fakettl_2_then_real"): "bypassed",
    ("combo:quic-strict", "quic_initial_with_blocked_sni"): "blocked",
    ("combo:quic-strict", "quic_initial_with_fake_decoy"): "bypassed",
    ("combo:quic-strict", "large_tls_record_single_packet"): "bypassed",
    ("combo:quic-strict", "long_lived_flow_post_blackhole"): "blocked",
    # all-tcp-and-blackhole = rst + sni-replace + ip-blackhole + mtu-clamp
    ("combo:all-tcp-and-blackhole", "split_offset_3_chlo"): "blocked",
    ("combo:all-tcp-and-blackhole", "tlsrandrec_profile_a"): "bypassed",
    ("combo:all-tcp-and-blackhole", "fakettl_2_then_real"): "blocked",
    ("combo:all-tcp-and-blackhole", "quic_initial_with_blocked_sni"): "bypassed",
    ("combo:all-tcp-and-blackhole", "quic_initial_with_fake_decoy"): "bypassed",
    ("combo:all-tcp-and-blackhole", "large_tls_record_single_packet"): "blocked",
    ("combo:all-tcp-and-blackhole", "long_lived_flow_post_blackhole"): "blocked",
    # all-five = every pattern
    ("combo:all-five", "split_offset_3_chlo"): "blocked",
    ("combo:all-five", "tlsrandrec_profile_a"): "bypassed",
    ("combo:all-five", "fakettl_2_then_real"): "blocked",
    ("combo:all-five", "quic_initial_with_blocked_sni"): "blocked",
    ("combo:all-five", "quic_initial_with_fake_decoy"): "bypassed",
    ("combo:all-five", "large_tls_record_single_packet"): "blocked",
    ("combo:all-five", "long_lived_flow_post_blackhole"): "blocked",
}

EXPECTED = {**_PATTERN_TABLE, **_COMBINATION_TABLE}


class ReplayMatrixTests(unittest.TestCase):
    def test_full_matrix_verdicts_match_expectations(self):
        matrix = _load_matrix()
        fixtures_dir = os.path.join(TSPU_DIR, "fixtures")
        with tempfile.TemporaryDirectory() as out_dir:
            report = replay.replay_matrix(matrix, fixtures_dir, out_dir)
            seen = set()
            for cell in report["cells"]:
                key = (cell["pattern_id"], cell["desync_mode_id"])
                seen.add(key)
                self.assertIn(key, EXPECTED, f"unexpected cell {key}")
                self.assertEqual(
                    cell["verdict"],
                    EXPECTED[key],
                    f"verdict mismatch for cell {key}: got {cell['verdict']}",
                )
            self.assertEqual(seen, set(EXPECTED.keys()), "matrix missing expected cells")
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

    def test_combination_cells_carry_matched_pattern_ids(self):
        matrix = _load_matrix()
        fixtures_dir = os.path.join(TSPU_DIR, "fixtures")
        with tempfile.TemporaryDirectory() as out_dir:
            report = replay.replay_matrix(matrix, fixtures_dir, out_dir)
        combo_cells = [c for c in report["cells"] if c["pattern_id"].startswith("combo:")]
        self.assertGreater(len(combo_cells), 0)
        for cell in combo_cells:
            self.assertIn("combination_member_ids", cell["evidence"])
            self.assertIn("matched_pattern_ids", cell["evidence"])
            if cell["verdict"] == "blocked":
                self.assertGreater(
                    len(cell["evidence"]["matched_pattern_ids"]),
                    0,
                    f"combo cell {cell['pattern_id']} blocked but no member matched",
                )
            else:
                self.assertEqual(cell["evidence"]["matched_pattern_ids"], [])


if __name__ == "__main__":
    unittest.main()
