#!/usr/bin/env python3
"""Regression tests for fail-closed Android acceptance evidence."""

import argparse
import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "ci/run-android-local-acceptance.py"
SPEC = importlib.util.spec_from_file_location("android_acceptance", SOURCE)
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)

TEST = "example.Test#roundTrip"


def transcript(code=0, final=-1, name="roundTrip"):
    return (
        f"INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test={name}\n"
        "INSTRUMENTATION_STATUS_CODE: 1\n"
        f"INSTRUMENTATION_STATUS: class=example.Test\nINSTRUMENTATION_STATUS: test={name}\n"
        f"INSTRUMENTATION_STATUS_CODE: {code}\nINSTRUMENTATION_CODE: {final}\n"
    )


class EvidenceTests(unittest.TestCase):
    def test_preparation_failure_summary_excludes_raw_output_and_secrets(self):
        import json

        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            (out / "build.log").write_text(
                "private-token=do-not-export\nNo space left on device\n"
                "> Task :app:compileGithubFullDebugKotlin FAILED\n"
            )
            result = runner.preparation_summary(
                out, {"status": "failed", "error": "private exception"}
            )
            self.assertEqual("failed", result["status"])
            self.assertEqual(["apk-build"], result["outputs_present"])
            self.assertEqual(
                ["disk-full", "kotlin-compile"], result["diagnostic_codes"]
            )
            self.assertNotIn("private", json.dumps(result))
            self.assertNotIn(temporary, json.dumps(result))

    def test_preparation_summary_retains_failure_when_build_did_not_start(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = runner.preparation_summary(Path(temporary), {"status": "blocked"})
            self.assertEqual("blocked", result["status"])
            self.assertEqual([], result["outputs_present"])
            self.assertEqual([], result["diagnostic_codes"])

    def test_preparation_summary_distinguishes_post_build_failures(self):
        for error, expected in (
            ("source changed during APK preparation", "source-changed"),
            ("build did not produce required app APK", "app-apk-missing"),
            ("build did not produce required test APK", "test-apk-missing"),
        ):
            with tempfile.TemporaryDirectory() as temporary, self.subTest(error=error):
                result = runner.preparation_summary(
                    Path(temporary), {"status": "failed", "error": error}
                )
                self.assertEqual([expected], result["diagnostic_codes"])

    def test_runtime_summary_exports_only_known_test_frame_and_codes(self):
        import json

        method = runner.SCENARIOS["android-xray"][2]
        class_name = method.split("#")[0]
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            (out / "test-3").mkdir()
            (out / "test-3/instrumentation.log").write_text(
                "INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: password=private-secret\n"
                f"  at {class_name}.peerLossAndRecoveryPreservesTunAndRejectsDirectBypass(XrayProviderE2ETest.kt:507)\n"
                f"  at {class_name}$helper.invoke(XrayProviderE2ETest.kt:512)\n"
                "  at private.secret.Handler.execute(Private.kt:42)\n"
                f"  at {class_name}.other(/private/secret.kt:60)\n"
                "INSTRUMENTATION_STATUS_CODE: -2\nINSTRUMENTATION_CODE: -1\n"
            )
            report = {
                "scenario_id": "android-xray",
                "status": "failed",
                "phase": "instrumentation",
                "active_method": method,
                "error_kind": "ValueError",
                "error": "private-secret",
                "cleanup": {"passed": True, "errors": ["private-secret"]},
            }
            summary = runner.runtime_summary(out, report)
            self.assertEqual("failed", summary["status"])
            self.assertEqual("instrumentation", summary["phase"])
            self.assertEqual(method, summary["method"])
            self.assertEqual("ValueError", summary["exception_kind"])
            self.assertEqual([-2], summary["instrumentation_status_codes"])
            self.assertEqual([-1], summary["instrumentation_final_codes"])
            self.assertEqual(["assertion-failed"], summary["diagnostic_codes"])
            self.assertEqual(
                [
                    {"class": class_name, "line": 507},
                    {"class": class_name, "line": 512},
                ],
                summary["test_frames"],
            )
            serialized = json.dumps(summary)
            for secret in ("private", "password", temporary, "stack=", "Stopped peer"):
                self.assertNotIn(secret, serialized)
            self.assertEqual("private-secret", report["error"])

    def test_runtime_summary_read_error_does_not_change_verdict(self):
        from unittest.mock import patch

        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            (out / "peer.log").write_text("private-secret")
            with patch.object(Path, "open", side_effect=OSError("private-secret")):
                summary = runner.runtime_summary(out, {"status": "passed"})
            self.assertEqual("passed", summary["status"])
            self.assertEqual(["diagnostic-read-failed"], summary["diagnostic_codes"])

    def test_runtime_summary_rejects_unknown_metadata(self):
        import json

        with tempfile.TemporaryDirectory() as temporary:
            summary = runner.runtime_summary(
                Path(temporary),
                {
                    "scenario_id": "private-secret",
                    "status": "private-secret",
                    "phase": "private-secret",
                    "active_method": "private-secret",
                    "error_kind": "private-secret",
                    "checks": [{"id": "private-secret", "passed": True}],
                },
            )
            self.assertNotIn("private", json.dumps(summary))
            self.assertEqual("failed", summary["status"])
            self.assertEqual("unknown", summary["phase"])
            self.assertEqual("other", summary["exception_kind"])
            self.assertIsNone(summary["method"])
            self.assertEqual([], summary["completed_methods"])

    def test_runtime_summary_bounds_codes_and_source_frames(self):
        method = runner.SCENARIOS["android-xray"][0]
        class_name = method.split("#")[0]
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            (out / "test-1").mkdir()
            (out / "test-1/instrumentation.log").write_text(
                "INSTRUMENTATION_STATUS_CODE: -9999\n"
                + "INSTRUMENTATION_STATUS_CODE: 1\n" * 100
                + "".join(
                    f" at {class_name}.test(XrayProviderE2ETest.kt:{line})\n"
                    for line in range(1, 100)
                )
            )
            summary = runner.runtime_summary(
                out,
                {
                    "scenario_id": "android-xray",
                    "status": "failed",
                    "active_method": method,
                },
            )
            self.assertEqual([1] * 16, summary["instrumentation_status_codes"])
            self.assertEqual(16, len(summary["test_frames"]))

    def test_runtime_summary_preserves_success_and_preinstrumentation_failure(self):
        method = runner.SCENARIOS["android-network"][0]
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            passed = runner.runtime_summary(
                out,
                {
                    "scenario_id": "android-network",
                    "status": "passed",
                    "phase": "complete",
                    "checks": [{"id": method.split("#")[1], "passed": True}],
                    "cleanup": {"passed": True},
                },
            )
            self.assertEqual("passed", passed["status"])
            self.assertEqual([method], passed["completed_methods"])
            self.assertTrue(passed["cleanup_passed"])
            failed = runner.runtime_summary(
                out,
                {
                    "scenario_id": "android-network",
                    "status": "failed",
                    "phase": "peer-ready",
                    "error_kind": "RuntimeError",
                    "error": "owned fixture readiness timed out",
                },
            )
            self.assertEqual(["fixture-readiness-timeout"], failed["diagnostic_codes"])
            self.assertEqual("peer-ready", failed["phase"])
            self.assertIsNone(failed["method"])

    def test_exact_completed_test_is_accepted(self):
        runner.parse_instrumentation(transcript(), TEST)

    def test_skips_failures_crash_and_incomplete_are_rejected(self):
        for output in (
            transcript(-3),
            transcript(-4),
            transcript(-2),
            transcript(final=0),
            transcript(name="unrelated"),
            transcript() * 2,
            "OK (1 test)",
            transcript().replace("INSTRUMENTATION_CODE: -1", ""),
        ):
            with self.subTest(output=output), self.assertRaises(ValueError):
                runner.parse_instrumentation(output, TEST)

    def test_private_numeric_endpoints_only(self):
        for address in ("10.0.2.2", "127.0.0.1", "192.168.105.2", "172.16.0.2"):
            self.assertEqual(address, runner.local_host(address))
        for address in ("0.0.0.0", "8.8.8.8", "192.0.2.1", "example.com", "::1"):
            with (
                self.subTest(address=address),
                self.assertRaises(argparse.ArgumentTypeError),
            ):
                runner.local_host(address)

    def test_prepared_apks_reject_drift_and_wrong_abi(self):
        import json

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            app = root / "app.apk"
            test = root / "test.apk"
            app.write_bytes(b"app")
            test.write_bytes(b"test")
            metadata = {
                "source_sha": "source",
                "source_tree_sha256": runner.source_fingerprint(),
                "abi": "arm64-v8a",
                "apks": {
                    "app": {"path": str(app), "sha256": runner.digest(app)},
                    "test": {"path": str(test), "sha256": runner.digest(test)},
                },
            }
            (root / "apk-metadata.json").write_text(json.dumps(metadata))
            self.assertEqual(metadata, runner.prepared(root, "arm64-v8a", "source"))
            with self.assertRaises(ValueError):
                runner.prepared(root, "x86_64", "source")
            with self.assertRaises(ValueError):
                runner.prepared(root, "arm64-v8a", "different")
            app.write_bytes(b"changed")
            with self.assertRaises(ValueError):
                runner.prepared(root, "arm64-v8a", "source")

    def test_missing_real_xray_blocks_before_gradle(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaises(runner.Blocked):
                runner.prepare(root, "arm64-v8a", "source", root / "absent")
            self.assertFalse((root / "build.log").exists())

    def test_cleanup_stops_only_owned_process(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = runner.OwnedProcess(
                [sys.executable, "-c", "import time; time.sleep(60)"],
                root / "first.log",
            )
            second = runner.OwnedProcess(
                [sys.executable, "-c", "import time; time.sleep(60)"],
                root / "second.log",
            )
            try:
                first.close()
                self.assertIsNotNone(first.process.poll())
                self.assertIsNone(second.process.poll())
            finally:
                first.close()
                second.close()

    def test_cleanup_stops_descendant_after_leader_exit(self):
        with tempfile.TemporaryDirectory() as temporary:
            script = "import subprocess,sys; subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])"
            child = runner.OwnedProcess(
                [sys.executable, "-c", script], Path(temporary) / "child.log"
            )
            try:
                child.process.wait(timeout=5)
                self.assertTrue(runner.group_has_live_process(child.process.pid))
                child.close()
                self.assertFalse(runner.group_has_live_process(child.process.pid))
            finally:
                child.close()

    def test_every_scenario_requires_exact_methods(self):
        self.assertEqual(3, len(runner.SCENARIOS["android-xray"]))
        self.assertIn(
            "peerLossAndRecoveryPreservesTunAndRejectsDirectBypass",
            " ".join(runner.SCENARIOS["android-xray"]),
        )
        self.assertIn(
            "localAcceptanceTunTcpUdpFaultAndRecovery",
            " ".join(runner.SCENARIOS["android-network"]),
        )


if __name__ == "__main__":
    unittest.main()
