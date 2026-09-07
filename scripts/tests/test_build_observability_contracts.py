from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"
OBSERVABILITY_ACTION = (
    ROOT / ".github/actions/upload-build-observability/action.yml"
)


def job_source(name: str) -> str:
    source = CI_WORKFLOW.read_text(encoding="utf-8")
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError(f"missing {name} CI job")
    return match.group(0)


class BuildObservabilityContractsTest(unittest.TestCase):
    def test_compile_heavy_gradle_jobs_emit_profiles(self) -> None:
        expected_minimum_profiles = {
            "rust-native-packaging": 1,
            "rust-native-x86_64": 1,
            "pluggable-transport-assets": 1,
            "build-android-debug": 1,
            "build-android-tests": 2,
            "verify-roborazzi": 1,
            "release-verification": 1,
            "gradle-static-analysis": 2,
            "android-macrobenchmark": 1,
            "android-instrumentation-apks": 1,
        }

        for job_name, expected_count in expected_minimum_profiles.items():
            with self.subTest(job=job_name):
                self.assertGreaterEqual(
                    job_source(job_name).count("--profile"),
                    expected_count,
                )

    def test_compile_jobs_always_upload_unique_observability_artifacts(self) -> None:
        artifact_names = {
            "rust-native-packaging": "build-observability-native-${{ matrix.abi.name }}",
            "rust-native-x86_64": "build-observability-native-x86_64",
            "pluggable-transport-assets": "build-observability-pluggable-transports",
            "build-android-debug": "build-observability-debug-${{ matrix.shard.id }}",
            "build-android-tests": "build-observability-android-tests",
            "verify-roborazzi": "build-observability-roborazzi",
            "release-verification": "build-observability-release-${{ matrix.shard.id }}",
            "diagnostics-probes-facade": "build-observability-diagnostics-probes",
            "rust-lint": "build-observability-rust-lint",
            "rust-cross-check": "build-observability-rust-cross-check",
            "rust-workspace-tests": "build-observability-rust-workspace-tests",
            "gradle-static-analysis": "build-observability-gradle-static-analysis",
            "android-macrobenchmark": "build-observability-android-macrobenchmark",
            "android-instrumented-tests": "build-observability-android-instrumented-${{ matrix.device }}",
            "android-instrumentation-apks": "build-observability-instrumentation-apks",
            "android-network-e2e": "build-observability-android-network-e2e",
            "android-journeys": "build-observability-android-journeys",
            "android-relay-emulator-smoke": "build-observability-android-relay-smoke",
        }

        for job_name, artifact_name in artifact_names.items():
            with self.subTest(job=job_name):
                source = job_source(job_name)
                self.assertIn("uses: ./.github/actions/upload-build-observability", source)
                self.assertIn("if: always()", source)
                self.assertIn(f"artifact-name: {artifact_name}", source)

    def test_script_owned_gradle_invocations_emit_profiles(self) -> None:
        for script_name in (
            "run-android-e2e-emulator.sh",
            "run-android-journeys-emulator.sh",
            "run-android-relay-emulator-smoke.sh",
        ):
            with self.subTest(script=script_name):
                source = (ROOT / "scripts/ci" / script_name).read_text(encoding="utf-8")
                self.assertIn("--profile", source)

    def test_observability_action_captures_profiles_and_cache_stats(self) -> None:
        source = OBSERVABILITY_ACTION.read_text(encoding="utf-8")

        self.assertIn("build/reports/profile", source)
        self.assertIn("sccache --show-stats", source)
        self.assertIn("name: Upload build observability\n      if: always()", source)
        self.assertIn("if-no-files-found: warn", source)
        self.assertIn("retention-days: 7", source)
        self.assertIn(
            "actions/upload-artifact@043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
            source,
        )

    def test_workflow_only_gate_checks_observability_contract_and_action_pin(self) -> None:
        source = job_source("workflow-only-contracts")

        self.assertIn("scripts.tests.test_build_observability_contracts", source)
        self.assertIn(
            ".github/actions/upload-build-observability/action.yml",
            source,
        )

    def test_full_ci_android_tests_run_new_performance_contracts(self) -> None:
        source = job_source("build-android-tests")

        self.assertIn("scripts.tests.test_build_observability_contracts", source)
        self.assertIn("scripts.tests.test_measure_native_abi_parallelism", source)


if __name__ == "__main__":
    unittest.main()
