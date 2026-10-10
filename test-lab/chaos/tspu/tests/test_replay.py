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


from runner.expectations import load_expected_verdicts, validate_report  # noqa: E402
from pathlib import Path

EXPECTED = load_expected_verdicts(Path(TSPU_DIR))

class ReplayMatrixTests(unittest.TestCase):
    def test_full_matrix_verdicts_match_expectations(self):
        matrix = _load_matrix()
        fixtures_dir = os.path.join(TSPU_DIR, "fixtures")
        with tempfile.TemporaryDirectory() as out_dir:
            report = replay.replay_matrix(matrix, fixtures_dir, out_dir)
            report_path = Path(out_dir) / "verdict-report.json"
            report_path.write_text(json.dumps(report), encoding="utf-8")
            receipt = validate_report(report_path, Path(TSPU_DIR).parents[2])
            self.assertEqual("pass", receipt["gateVerdict"], receipt["errors"])
            self.assertFalse(receipt["releaseAcceptance"])
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
