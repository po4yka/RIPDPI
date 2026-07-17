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
DNS_IPV6_EVIDENCE_WORKFLOW = ROOT / ".github/workflows/dns-ipv6-killswitch-evidence.yml"


def workflow_job(source: str, job_name: str) -> str:
    match = re.search(
        rf"(?ms)^  {re.escape(job_name)}:\n.*?(?=^  [\w-]+:\n|\Z)",
        source,
    )
    if match is None:
        raise AssertionError(f"missing {job_name} job")
    return match.group(0)


def nightly_coverage_job(source: str) -> str:
    return workflow_job(source, "nightly-rust-coverage")


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

    def test_coverage_skips_unused_android_targets_and_ndk(self) -> None:
        for job_name in ("coverage", "nightly-rust-coverage"):
            job = workflow_job(CI_WORKFLOW.read_text(encoding="utf-8"), job_name)
            self.assertIn('rust-targets: ""', job)
            self.assertIn('setup-android-ndk: "false"', job)
            self.assertIn('setup-sccache: "false"', job)

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

    def test_device_smokes_use_the_ci_native_abi_override(self) -> None:
        packet_smoke = PACKET_SMOKE_SCRIPT.read_text(encoding="utf-8")
        evidence_workflow = DNS_IPV6_EVIDENCE_WORKFLOW.read_text(encoding="utf-8")

        self.assertIn("-Pripdpi.nativeAbisOverride=${gradle_abi}", packet_smoke)
        self.assertNotIn("-Pripdpi.localNativeAbis=", packet_smoke)
        self.assertIn("-Pripdpi.nativeAbisOverride=$device_abi", evidence_workflow)
        self.assertNotIn("-Pripdpi.localNativeAbis=", evidence_workflow)


if __name__ == "__main__":
    unittest.main()
