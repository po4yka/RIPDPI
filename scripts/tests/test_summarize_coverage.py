"""Regression tests for coverage summary status and missing data."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from scripts.ci.summarize_coverage import line_coverage_from_xml


SCRIPT = Path(__file__).resolve().parents[1] / "ci/summarize_coverage.py"


class CoverageSummaryTest(unittest.TestCase):
    def test_summary_distinguishes_advisory_targets_from_enforced_thresholds(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "coverage.xml"
            report.write_text('<report><counter type="LINE" missed="60" covered="40"/></report>')
            for enforce in (False, True):
                with self.subTest(enforce=enforce):
                    result = subprocess.run(
                        [sys.executable, str(SCRIPT), "--aggregate-xml", str(report)]
                        + (["--enforce"] if enforce else []),
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertEqual(result.returncode, 1 if enforce else 0)
                    heading = "Threshold" if enforce else "Advisory target"
                    self.assertIn(f"| Scope | Line coverage | {heading} |", result.stdout)
                    self.assertIn("| Kotlin aggregate | 40.00% | 65% |", result.stdout)
                    if enforce:
                        self.assertIn("is below 65.00%", result.stderr)
                    else:
                        self.assertEqual(result.stderr, "")

    def test_missing_line_counter_is_an_error_but_explicit_empty_counter_is_valid(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / "coverage.xml"
            report.write_text('<report/>')
            with self.assertRaisesRegex(ValueError, "No LINE counter"):
                line_coverage_from_xml(report)
            report.write_text('<report><counter type="LINE" missed="0" covered="0"/></report>')
            self.assertEqual(line_coverage_from_xml(report), 100.0)


if __name__ == "__main__":
    unittest.main()
