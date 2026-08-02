#!/usr/bin/env python3
"""Regression tests for nightly coverage test selection."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
FLEET_FIXTURES_WORKFLOW = ROOT / ".github/workflows/fleet-fixtures.yml"
SETUP_ACTION = ROOT / ".github/actions/setup-android-rust/action.yml"
COVERAGE_SCRIPT = ROOT / "scripts/ci/run-rust-coverage.sh"
RELAY_SMOKE_SCRIPT = ROOT / "scripts/ci/run-android-relay-emulator-smoke.sh"
PACKET_SMOKE_SCRIPT = ROOT / "scripts/ci/run-android-packet-smoke.sh"


def workflow_job(source: str, job_name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\n.*?(?=^  [\w-]+:\n|\Z)",
        source,
    )
    if match is None:
        raise AssertionError(f"missing {job_name} job")
    return match.group(0)


def nightly_rust_coverage_job(source: str) -> str:
    return workflow_job(source, "nightly-rust-coverage")


class NightlyCoverageSelectionTest(unittest.TestCase):
    def test_nightly_rust_coverage_includes_ignored_low_cost_tests(self) -> None:
        job = nightly_rust_coverage_job(CI_WORKFLOW.read_text(encoding="utf-8"))
        self.assertIn('RIPDPI_RUST_COVERAGE_INCLUDE_IGNORED: "1"', job)
        self.assertIn("timeout-minutes: 90", job)
        self.assertIn("bash scripts/ci/run-rust-coverage.sh", job)
        self.assertNotIn("coverageReport", job)

    def test_coverage_script_keeps_ignored_tests_opt_in(self) -> None:
        source = COVERAGE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn(
            'include_ignored="${RIPDPI_RUST_COVERAGE_INCLUDE_IGNORED:-0}"',
            source,
        )
        self.assertIn("--skip 'real_tun_'", source)
        self.assertIn("--skip 'so_bindtodevice_'", source)

    def test_coverage_tests_default_to_the_report_package_scope(self) -> None:
        source = COVERAGE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn('test_package_specs="$report_package_specs"', source)
        self.assertIn('test_scope_args+=(--package "$package")', source)
        self.assertIn('"${test_scope_args[@]}"', source)

    def test_kotlin_only_jobs_do_not_provision_rust_or_ndk(self) -> None:
        ci_source = CI_WORKFLOW.read_text(encoding="utf-8")
        for job_name in (
            "build-android-tests",
            "verify-roborazzi",
            "gradle-static-analysis",
        ):
            job = workflow_job(ci_source, job_name)
            self.assertIn('setup-rust: "false"', job)
            self.assertIn('setup-android-ndk: "false"', job)

        fleet_job = workflow_job(
            FLEET_FIXTURES_WORKFLOW.read_text(encoding="utf-8"), "fleet-fixtures"
        )
        self.assertIn('setup-rust: "false"', fleet_job)
        self.assertIn('setup-android-ndk: "false"', fleet_job)

    def test_composite_setup_can_skip_unused_toolchains(self) -> None:
        source = SETUP_ACTION.read_text(encoding="utf-8")
        self.assertIn("setup-java:\n", source)
        self.assertIn("setup-rust:\n", source)
        self.assertIn("setup-sccache:\n", source)
        self.assertIn("setup-android-ndk:\n", source)
        self.assertIn("if: inputs.setup-java == 'true'", source)
        self.assertIn("if: inputs.setup-rust == 'true'", source)
        self.assertIn("inputs.setup-android-ndk == 'true'", source)

    def test_android_sdk_cache_is_separated_by_ndk_installation_mode(self) -> None:
        source = SETUP_ACTION.read_text(encoding="utf-8")

        self.assertIn("-ndk-${{ inputs.setup-android-ndk }}-", source)
        self.assertIn(
            "a ~/.android cache hit is not installation evidence",
            source,
        )

    def test_rust_coverage_skips_android_and_gradle_setup(self) -> None:
        for job_name in ("rust-coverage", "nightly-rust-coverage"):
            job = workflow_job(CI_WORKFLOW.read_text(encoding="utf-8"), job_name)
            self.assertIn('setup-java: "false"', job)
            self.assertIn('setup-gradle: "false"', job)
            self.assertIn('skip-android-sdk-ndk: "true"', job)

    def test_kotlin_coverage_never_installs_rust_or_ndk(self) -> None:
        for job_name in ("kotlin-coverage", "nightly-kotlin-coverage"):
            with self.subTest(job=job_name):
                job = workflow_job(CI_WORKFLOW.read_text(encoding="utf-8"), job_name)
                self.assertIn('setup-rust: "false"', job)
                self.assertIn('setup-android-ndk: "false"', job)
                self.assertIn('setup-sccache: "false"', job)
                self.assertIn("coverageReport -Pripdpi.skipNativeBuild=true", job)

    def test_pr_coverage_jobs_use_their_respective_change_route(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        kotlin_job = workflow_job(source, "kotlin-coverage")
        rust_job = workflow_job(source, "rust-coverage")

        self.assertIn("outputs.run_kotlin_coverage == 'true'", kotlin_job)
        self.assertNotIn("outputs.run_rust_coverage", kotlin_job)
        self.assertIn("outputs.run_rust_coverage == 'true'", rust_job)
        self.assertNotIn("outputs.run_kotlin_coverage", rust_job)
        for job in (kotlin_job, rust_job):
            self.assertNotIn("run-heavy-ci", job)
            self.assertNotIn("run-coverage", job)
            self.assertIn("run_nightly_coverage != 'true'", job)

    def test_nightly_coverage_runs_kotlin_and_rust_lanes_separately(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        kotlin_job = workflow_job(source, "nightly-kotlin-coverage")
        rust_job = workflow_job(source, "nightly-rust-coverage")

        self.assertIn("github.event.schedule == '43 4 * * 3'", kotlin_job)
        self.assertIn("github.event.schedule == '43 4 * * 3'", rust_job)
        self.assertIn("nightly-kotlin-coverage-artifacts", kotlin_job)
        self.assertIn("nightly-coverage-artifacts", rust_job)

    def test_scheduled_ci_rotates_high_signal_lanes(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        for cron in (
            'cron: "23 3 * * *"',
            'cron: "7 4 * * 1"',
            'cron: "19 4 * * 2"',
            'cron: "43 4 * * 3"',
            'cron: "11 4 * * 4"',
            'cron: "27 4 * * 5"',
        ):
            self.assertIn(cron, source)

        expected_lanes = {
            "cargo-deny-advisories": "23 3 * * *",
            "rust-criterion-bench": "7 4 * * 1",
            "rust-native-soak": "19 4 * * 2",
            "rust-native-load": "19 4 * * 2",
            "linux-tun-soak": "19 4 * * 2",
            "nightly-kotlin-coverage": "43 4 * * 3",
            "nightly-rust-coverage": "43 4 * * 3",
            "android-macrobenchmark": "11 4 * * 4",
            "android-relay-emulator-smoke": "27 4 * * 5",
            "linux-tun-e2e": "27 4 * * 5",
            "so-bindtodevice-e2e": "27 4 * * 5",
        }
        for job_name, cron in expected_lanes.items():
            job = workflow_job(source, job_name)
            self.assertIn(f"github.event.schedule == '{cron}'", job)

    def test_macrobenchmark_uses_the_ci_native_abi_override(self) -> None:
        source = CI_WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(
            source,
            r"(?m):baselineprofile:pixel6Api34AtdDebugAndroidTest .*\n\s*-Pripdpi.nativeAbisOverride=x86_64",
        )
        self.assertNotIn("-Pripdpi.localNativeAbis=x86_64", source)

    def test_relay_smoke_uses_the_ci_native_abi_override(self) -> None:
        source = RELAY_SMOKE_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("-Pripdpi.nativeAbisOverride=x86_64", source)
        self.assertNotIn("-Pripdpi.localNativeAbis=x86_64", source)

    def test_packet_smoke_uses_the_ci_native_abi_override(self) -> None:
        packet_smoke = PACKET_SMOKE_SCRIPT.read_text(encoding="utf-8")

        self.assertIn("-Pripdpi.nativeAbisOverride=${gradle_abi}", packet_smoke)
        self.assertNotIn("-Pripdpi.localNativeAbis=", packet_smoke)

if __name__ == "__main__":
    unittest.main()
