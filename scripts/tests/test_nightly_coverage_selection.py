#!/usr/bin/env python3
"""Regression tests for nightly coverage test selection."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
COVERAGE_SCRIPT = ROOT / "scripts/ci/run-rust-coverage.sh"


def nightly_coverage_job(source: str) -> str:
    match = re.search(
        r"(?ms)^  nightly-rust-coverage:\n.*?(?=^  [\w-]+:\n|\Z)",
        source,
    )
    if match is None:
        raise AssertionError("missing nightly-rust-coverage job")
    return match.group(0)


class NightlyCoverageSelectionTest(unittest.TestCase):
    def test_nightly_coverage_does_not_enable_all_ignored_tests(self) -> None:
        job = nightly_coverage_job(CI_WORKFLOW.read_text(encoding="utf-8"))
        self.assertNotIn("RIPDPI_RUST_COVERAGE_INCLUDE_IGNORED", job)
        self.assertIn("timeout-minutes: 90", job)
        self.assertIn("coverageReport -Pripdpi.skipNativeBuild=true", job)
        self.assertIn("bash scripts/ci/run-rust-coverage.sh", job)

    def test_coverage_script_keeps_ignored_tests_opt_in(self) -> None:
        source = COVERAGE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            'include_ignored="${RIPDPI_RUST_COVERAGE_INCLUDE_IGNORED:-0}"',
            source,
        )

    def test_macrobenchmark_uses_the_ci_native_abi_override(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(
            source,
            r"(?m):baselineprofile:pixel6Api34AtdDebugAndroidTest .*\n\s*-Pripdpi.nativeAbisOverride=x86_64",
        )
        self.assertNotIn("-Pripdpi.localNativeAbis=x86_64", source)


if __name__ == "__main__":
    unittest.main()
