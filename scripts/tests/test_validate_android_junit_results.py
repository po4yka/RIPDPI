#!/usr/bin/env python3
"""Contract tests for connected Android JUnit result validation."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts/ci/validate_android_junit_results.py"
FIXTURES = Path(__file__).with_name("fixtures") / "android-junit-results"
PREFLIGHT_TEST = (
    "com.poyka.ripdpi.e2e.EnvironmentPreflightE2ETest"
    "#environmentSupportsFixtureReachabilityAndVpnConsent"
)


class ValidateAndroidJunitResultsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.results = Path(self.temp.name) / "results"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_validator(
        self,
        *arguments: str,
        required_test: str = PREFLIGHT_TEST,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "python3",
                str(VALIDATOR),
                str(self.results),
                "--require-test",
                required_test,
                *arguments,
            ],
            capture_output=True,
            check=False,
            text=True,
        )

    def install_fixture(self, name: str) -> None:
        self.results.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(FIXTURES / name, self.results / "TEST-results.xml")

    def test_required_passing_testcase_is_accepted(self) -> None:
        self.install_fixture("pass.xml")

        completed = self.run_validator()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("validated 2 testcases", completed.stdout)

    def test_expected_total_count_is_enforced(self) -> None:
        self.install_fixture("pass.xml")

        completed = self.run_validator("--expected-total-count", "1")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("result set contains 2 testcases, expected 1", completed.stderr)

    def test_minimum_total_count_is_enforced(self) -> None:
        self.install_fixture("pass.xml")

        completed = self.run_validator("--minimum-total-count", "3")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("result set contains 2 testcases, minimum is 3", completed.stderr)

    def test_missing_result_directory_is_rejected(self) -> None:
        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("no JUnit XML files", completed.stderr)

    def test_empty_result_directory_is_rejected(self) -> None:
        self.results.mkdir(parents=True)

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("no JUnit XML files", completed.stderr)

    def test_zero_testcase_report_is_rejected(self) -> None:
        self.install_fixture("zero-tests.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("zero testcases", completed.stderr)

    def test_missing_required_testcase_is_rejected(self) -> None:
        self.install_fixture("pass.xml")

        completed = self.run_validator(required_test="example.MissingTest#neverRan")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("required testcase did not execute", completed.stderr)

    def test_skipped_required_testcase_is_rejected(self) -> None:
        self.install_fixture("skipped.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("required testcase was skipped", completed.stderr)

    def test_forbid_skips_rejects_any_skipped_testcase(self) -> None:
        self.install_fixture("skipped.xml")

        completed = self.run_validator("--forbid-skips")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("skipped testcase", completed.stderr)

    def test_failure_is_rejected(self) -> None:
        self.install_fixture("failure.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("failed testcase", completed.stderr)

    def test_error_is_rejected(self) -> None:
        self.install_fixture("error.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("errored testcase", completed.stderr)

    def test_duplicate_testcase_is_rejected(self) -> None:
        self.install_fixture("duplicate.xml")

        completed = self.run_validator(
            "--expected-count",
            "1",
        )

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("duplicate testcase", completed.stderr)

    def test_malformed_xml_is_rejected(self) -> None:
        self.install_fixture("malformed.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("could not parse JUnit XML", completed.stderr)

    def test_unsupported_root_is_rejected(self) -> None:
        self.install_fixture("invalid-root.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("unsupported JUnit XML root", completed.stderr)

    def test_missing_aggregate_is_rejected(self) -> None:
        self.install_fixture("missing-aggregate.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("missing skipped aggregate", completed.stderr)

    def test_non_numeric_aggregate_is_rejected(self) -> None:
        self.install_fixture("non-numeric-aggregate.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("non-negative integer", completed.stderr)

    def test_tests_aggregate_must_match_testcases(self) -> None:
        self.install_fixture("mismatched-tests.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("tests aggregate mismatch", completed.stderr)

    def test_status_aggregate_must_match_testcase_elements(self) -> None:
        self.install_fixture("mismatched-status.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("failures aggregate mismatch", completed.stderr)

    def test_testsuites_root_aggregate_must_match_child_suites(self) -> None:
        self.install_fixture("mismatched-root-aggregate.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("tests aggregate mismatch", completed.stderr)

    def test_namespaced_multi_suite_report_is_counted_once(self) -> None:
        self.install_fixture("namespaced-multi-suite.xml")

        completed = self.run_validator()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("validated 2 testcases", completed.stdout)

    def test_status_element_outside_testcase_is_rejected(self) -> None:
        self.install_fixture("orphan-failure.xml")

        completed = self.run_validator()

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("status element outside testcase", completed.stderr)


if __name__ == "__main__":
    unittest.main()
