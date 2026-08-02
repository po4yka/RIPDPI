#!/usr/bin/env python3
"""Process-level contract tests for the Android network E2E runner."""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/ci/run-android-e2e-emulator.sh"
VALIDATOR = ROOT / "scripts/ci/validate_android_junit_results.py"
WORKFLOW = ROOT / ".github/workflows/ci.yml"
RESULTS_RELATIVE = Path("app/build/outputs/androidTest-results/connected")
VARIANT_RESULTS_RELATIVE = RESULTS_RELATIVE / "debug/flavors/githubFull"
WRONG_VARIANT_RESULTS_RELATIVE = RESULTS_RELATIVE / "githubFullDebug"

FAKE_GRADLE = r'''#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

runner_temp = Path(os.environ["RUNNER_TEMP"])
count_file = runner_temp / "gradle-count"
count = int(count_file.read_text(encoding="utf-8")) + 1 if count_file.exists() else 1
count_file.write_text(str(count), encoding="utf-8")
with (runner_temp / "gradle-args.jsonl").open("a", encoding="utf-8") as output:
    output.write(json.dumps(sys.argv[1:]) + "\n")

results_root = Path(
    "app/build/outputs/androidTest-results/connected/debug/flavors/githubFull"
)
if results_root.exists():
    print("result directory was not cleaned before Gradle", file=sys.stderr)
    raise SystemExit(90)
if count == 2 and os.environ.get("OMIT_SECOND_RESULTS") == "1":
    raise SystemExit(0)

if count == 2 and os.environ.get("WRITE_WRONG_VARIANT_ONLY") == "1":
    results_dir = Path("app/build/outputs/androidTest-results/connected/githubFullDebug")
else:
    results_dir = results_root
results_dir.mkdir(parents=True)
if count == 1:
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite tests="1" skipped="0" failures="0" errors="0">
  <testcase classname="com.poyka.ripdpi.e2e.EnvironmentPreflightE2ETest"
      name="environmentSupportsFixtureReachabilityAndVpnConsent" />
</testsuite>
"""
else:
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite tests="5" skipped="0" failures="0" errors="0">
  <testcase classname="com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest"
      name="luaLoadScriptLoadsBundledZapretScriptsAfterExtraction" />
  <testcase classname="com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest"
      name="luaLoadScriptMissingPathReturnsErrorString" />
  <testcase classname="com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest"
      name="luaLoadScriptRejectsPathOutsideSeededJail" />
  <testcase classname="com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest"
      name="strategyConfigValidationAcceptsRegistryYaml" />
  <testcase classname="com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest"
      name="strategyConfigValidationRejectsInvalidYaml" />
</testsuite>
"""
(results_dir / "TEST-network-e2e.xml").write_text(xml, encoding="utf-8")
'''


def write_executable(path: Path, content: str) -> None:
    path.write_text(textwrap.dedent(content), encoding="utf-8")
    path.chmod(0o755)


class RunAndroidE2eEmulatorTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.ci_dir = self.repo / "scripts/ci"
        self.ci_dir.mkdir(parents=True)
        shutil.copyfile(RUNNER, self.ci_dir / RUNNER.name)
        shutil.copyfile(VALIDATOR, self.ci_dir / VALIDATOR.name)
        write_executable(
            self.ci_dir / "android-emulator-helpers.sh",
            "#!/usr/bin/env bash\nadb_cmd() { return 0; }\n",
        )
        write_executable(
            self.ci_dir / "wait-for-android-package-manager.sh",
            "#!/usr/bin/env bash\nexit 0\n",
        )
        write_executable(self.repo / "gradlew", FAKE_GRADLE)
        self.runner_temp = self.repo / "runner-temp"
        self.runner_temp.mkdir()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_runner(self, **environment: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update({"RUNNER_TEMP": str(self.runner_temp), **environment})
        return subprocess.run(
            [
                "bash",
                "scripts/ci/run-android-e2e-emulator.sh",
                "push",
                "false",
                "false",
            ],
            cwd=self.repo,
            capture_output=True,
            check=False,
            env=env,
            text=True,
        )

    def test_runner_removes_stale_results_before_each_target(self) -> None:
        stale = self.repo / VARIANT_RESULTS_RELATIVE / "stale.xml"
        stale.parent.mkdir(parents=True)
        stale.write_text("<testsuite tests='0' />", encoding="utf-8")
        wrong_variant = self.repo / WRONG_VARIANT_RESULTS_RELATIVE / "keep.txt"
        wrong_variant.parent.mkdir(parents=True)
        wrong_variant.write_text("unrelated variant", encoding="utf-8")

        completed = self.run_runner()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(
            (self.runner_temp / "gradle-count").read_text(encoding="utf-8"), "2"
        )
        self.assertIn("validated 1 testcases", completed.stdout)
        self.assertIn("validated 5 testcases", completed.stdout)
        self.assertTrue(wrong_variant.is_file())

    def test_runner_uses_ci_native_abi_override(self) -> None:
        completed = self.run_runner()

        self.assertEqual(completed.returncode, 0, completed.stderr)
        invocations = [
            json.loads(line)
            for line in (self.runner_temp / "gradle-args.jsonl")
            .read_text(encoding="utf-8")
            .splitlines()
        ]
        self.assertEqual(len(invocations), 2)
        for arguments in invocations:
            self.assertIn("-Pripdpi.nativeAbisOverride=x86_64", arguments)
            self.assertNotIn("-Pripdpi.localNativeAbis=x86_64", arguments)

        self.assertIn(
            "-Pandroid.testInstrumentationRunnerArguments.class=com.poyka.ripdpi.jni.StrategyEngineJniInstrumentedTest",
            invocations[1],
        )

    def test_runner_rejects_missing_second_run_results(self) -> None:
        completed = self.run_runner(OMIT_SECOND_RESULTS="1")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("no JUnit XML files", completed.stderr)

    def test_runner_rejects_report_from_wrong_variant(self) -> None:
        completed = self.run_runner(WRITE_WRONG_VARIANT_ONLY="1")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("no JUnit XML files", completed.stderr)

    def test_runner_stops_before_gradle_when_result_cleanup_fails(self) -> None:
        bin_dir = self.repo / "bin"
        bin_dir.mkdir()
        write_executable(bin_dir / "rm", "#!/usr/bin/env bash\nexit 73\n")

        completed = self.run_runner(PATH=f"{bin_dir}:{os.environ['PATH']}")

        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("Failed to clear Android JUnit results", completed.stderr)
        self.assertFalse((self.runner_temp / "gradle-count").exists())
        self.assertEqual(
            (self.runner_temp / "android-instrumented-target.txt")
            .read_text(encoding="utf-8")
            .strip(),
            "com.poyka.ripdpi.e2e.EnvironmentPreflightE2ETest",
        )

    def test_ci_always_runs_android_junit_contract_tests(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        job_start = workflow.index("  android-e2e-result-contracts:\n")
        job_end = workflow.index("\n  design-md-lint:\n", job_start)
        job = workflow[job_start:job_end]

        self.assertNotIn("\n    if:", job)
        self.assertNotIn("\n    needs:", job)
        self.assertIn("scripts.tests.test_validate_android_junit_results", job)
        self.assertIn("scripts.tests.test_run_android_e2e_emulator", job)
        self.assertIn("scripts.tests.test_start_local_network_fixture", job)

    def test_ci_bounds_connected_e2e_before_always_cleanup(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        job_start = workflow.index("  android-network-e2e:\n")
        job_end = workflow.index("\n  android-journeys:\n", job_start)
        job = workflow[job_start:job_end]
        step_start = job.index("      - name: Run connected Android network E2E tests\n")
        step_end = job.index("\n      - name: Capture fixture state\n", step_start)

        job_timeout_match = re.search(r"^    timeout-minutes: ([0-9]+)$", job, re.MULTILINE)
        step_timeout_match = re.search(
            r"^        timeout-minutes: ([0-9]+)$",
            job[step_start:step_end],
            re.MULTILINE,
        )
        self.assertIsNotNone(job_timeout_match)
        self.assertIsNotNone(step_timeout_match)
        assert job_timeout_match is not None
        assert step_timeout_match is not None
        job_timeout = int(job_timeout_match.group(1))
        step_timeout = int(step_timeout_match.group(1))
        self.assertGreaterEqual(job_timeout - step_timeout, 90)
        for cleanup_step in (
            "Capture fixture state",
            "Capture network E2E emulator diagnostics",
            "Stop emulator",
            "Stop local network fixture",
            "Upload Android network E2E artifacts",
        ):
            cleanup_start = job.index(f"      - name: {cleanup_step}\n")
            cleanup_end = job.find("\n      - name:", cleanup_start + 1)
            cleanup = job[cleanup_start:] if cleanup_end == -1 else job[cleanup_start:cleanup_end]
            self.assertGreater(cleanup_start, step_end)
            self.assertIn("\n        if: always()\n", cleanup)
            cleanup_timeout = re.search(
                r"^        timeout-minutes: ([0-9]+)$",
                cleanup,
                re.MULTILINE,
            )
            self.assertIsNotNone(cleanup_timeout)
            assert cleanup_timeout is not None
            self.assertLessEqual(int(cleanup_timeout.group(1)), 10)

    def test_ordinary_instrumented_matrix_validates_full_simple_and_jni_evidence(self) -> None:
        workflow = WORKFLOW.read_text(encoding="utf-8")
        job_start = workflow.index("  android-instrumented-tests:\n")
        job_end = workflow.index("\n  rust-loom:\n", job_start)
        job = workflow[job_start:job_end]

        self.assertIn("Validate GitHub Full instrumentation evidence", job)
        self.assertIn("Validate GitHub Simple instrumentation evidence", job)
        self.assertIn("Run JNI strategy instrumentation", job)
        self.assertIn("Validate JNI strategy instrumentation evidence", job)
        self.assertIn("if: matrix.api == 35", job)
        self.assertIn("StrategyEngineJniInstrumentedTest", job)
        self.assertIn("--expected-total-count 5", job)
        self.assertIn("--minimum-total-count 54", job)
        self.assertIn("Preserve GitHub Full instrumentation evidence", job)
        self.assertIn("Preserve GitHub Simple instrumentation evidence", job)
        self.assertIn("Preserve JNI strategy instrumentation evidence", job)
        self.assertIn("${{ runner.temp }}/android-instrumented-results/", job)


if __name__ == "__main__":
    unittest.main()
