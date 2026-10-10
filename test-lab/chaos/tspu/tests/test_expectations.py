"""The shared fixture gate must reject incomplete or corrupt evidence."""
from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest

from runner import replay
from runner.expectations import validate_report

TSPU_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = TSPU_DIR.parents[2]


class FixtureReceiptTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.path = self.root / "verdict-report.json"
        matrix = json.loads((TSPU_DIR / "matrix.json").read_text())
        self.report = replay.replay_matrix(matrix, str(TSPU_DIR / "fixtures"), str(self.root))

    def validate(self, report=None):
        self.path.write_text(json.dumps(self.report if report is None else report))
        return validate_report(self.path, REPO_ROOT)

    def assert_fails(self, report=None):
        receipt = self.validate(report)
        self.assertEqual("fail", receipt["gateVerdict"])
        self.assertFalse(receipt["releaseAcceptance"])
        self.assertTrue(receipt["errors"])

    def test_complete_matrix_accepts_negative_controls_without_release_acceptance(self):
        receipt = self.validate()
        self.assertEqual("pass", receipt["gateVerdict"], receipt["errors"])
        self.assertEqual("classifier-self-test", receipt["purpose"])
        self.assertFalse(receipt["releaseAcceptance"])
        self.assertEqual(63, receipt["coverage"]["cellCount"])
        self.assertEqual({"blocked": 23, "bypassed": 40, "degraded": 0, "inconclusive": 0},
                         receipt["coverage"]["totals"])

    def test_swapped_verdicts_fail_even_with_same_totals(self):
        blocked = next(c for c in self.report["cells"] if c["verdict"] == "blocked")
        bypassed = next(c for c in self.report["cells"] if c["verdict"] == "bypassed")
        blocked["verdict"], bypassed["verdict"] = bypassed["verdict"], blocked["verdict"]
        self.assert_fails()

    def test_missing_extra_duplicate_and_unknown_cells_fail(self):
        for mutation in ("missing", "extra", "duplicate", "unknown-verdict"):
            with self.subTest(mutation=mutation):
                report = deepcopy(self.report)
                if mutation == "missing":
                    report["cells"].pop()
                elif mutation == "extra":
                    cell = deepcopy(report["cells"][0]); cell["pattern_id"] = "unknown"
                    report["cells"].append(cell)
                elif mutation == "duplicate":
                    report["cells"].append(deepcopy(report["cells"][0]))
                else:
                    report["cells"][0]["verdict"] = "unknown"
                self.assert_fails(report)

    def test_wrong_modes_versions_totals_and_cell_types_fail(self):
        for field, value in (("mode", "live"), ("mode", "engine-capture"),
                             ("report_schema_version", 2), ("matrix_version", 11),
                             ("matrix_version", True), ("totals", {"blocked": 23}),
                             ("totals", {"blocked": True, "bypassed": 40, "degraded": 0, "inconclusive": 0}),
                             ("cells", []), ("cells", [None]), ("cells", {})):
            with self.subTest(field=field, value=value):
                report = deepcopy(self.report); report[field] = value
                self.assert_fails(report)

    def test_missing_truncated_empty_and_invalid_pcap_fail(self):
        path = self.root / self.report["cells"][0]["evidence"]["pcap_path"]
        original = path.read_bytes()
        for data in (None, b"", original[:24], original[:-1], b"not a pcap" * 10):
            with self.subTest(size=None if data is None else len(data)):
                if data is None:
                    path.unlink(missing_ok=True)
                else:
                    path.write_bytes(data)
                self.assert_fails()
        path.write_bytes(original)
        self.report["cells"][0]["evidence"]["pcap_packets"] += 1
        self.assert_fails()

    def test_absolute_traversal_symlink_and_shared_pcap_fail(self):
        for path in ("../escape.pcap", str(self.root / "capture.pcap")):
            with self.subTest(path=path):
                report = deepcopy(self.report); report["cells"][0]["evidence"]["pcap_path"] = path
                self.assert_fails(report)
        outside = self.root.parent / (self.root.name + "-outside.pcap")
        outside.write_bytes((self.root / self.report["cells"][0]["evidence"]["pcap_path"]).read_bytes())
        self.addCleanup(outside.unlink)
        (self.root / "escape.pcap").symlink_to(outside)
        report = deepcopy(self.report); report["cells"][0]["evidence"]["pcap_path"] = "escape.pcap"
        self.assert_fails(report)
        report = deepcopy(self.report)
        report["cells"][1]["evidence"] = deepcopy(report["cells"][0]["evidence"])
        self.assert_fails(report)

    def test_missing_malformed_and_non_object_report_fail_closed(self):
        receipt = validate_report(self.path, REPO_ROOT)
        self.assertEqual("fail", receipt["gateVerdict"])
        for value in ("{broken", "null", "[]"):
            self.path.write_text(value)
            receipt = validate_report(self.path, REPO_ROOT)
            self.assertEqual("fail", receipt["gateVerdict"])
            self.assertTrue(receipt["errors"])
