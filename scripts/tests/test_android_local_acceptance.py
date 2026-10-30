#!/usr/bin/env python3
"""Regression tests for fail-closed Android acceptance evidence."""

import argparse
import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

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
    def test_awg_peer_environment_pins_mtu_and_rejects_inherited_peer_scope(self):
        inherited = {
            "PATH": "/owned/bin",
            "RIPDPI_AWG_INTEROP_MTU": "invalid",
            "RIPDPI_AWG_INTEROP_CYCLES": "invalid",
            "RIPDPI_AWG_HTTP_ACCEPTANCE": "0",
            "RIPDPI_AWG_BIND_IP": "192.168.9.1",
            "RIPDPI_AWG_PEER_IP": "192.168.9.2",
        }
        with patch.dict(runner.os.environ, inherited, clear=True):
            local = runner.awg_peer_environment(None, None)
            self.assertEqual("1330", local["RIPDPI_AWG_INTEROP_MTU"])
            self.assertEqual("1", local["RIPDPI_AWG_INTEROP_CYCLES"])
            self.assertEqual("1", local["RIPDPI_AWG_HTTP_ACCEPTANCE"])
            self.assertEqual("/owned/bin", local["PATH"])
            self.assertNotIn("RIPDPI_AWG_BIND_IP", local)
            self.assertNotIn("RIPDPI_AWG_PEER_IP", local)
            lan = runner.awg_peer_environment("192.168.1.1", "192.168.1.2")
            self.assertEqual("192.168.1.1", lan["RIPDPI_AWG_BIND_IP"])
            self.assertEqual("192.168.1.2", lan["RIPDPI_AWG_PEER_IP"])
            self.assertEqual(inherited, dict(runner.os.environ))

    def test_logcat_scope_uses_both_exact_uids_and_device_time_without_tag_filter(self):
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            listing = (
                "package:com.poyka.ripdpi.baselineprofile uid:99999\n"
                "package:com.poyka.ripdpi uid:10329\n"
                "package:com.poyka.ripdpi.test uid:10348\n"
            )
            with patch.object(runner, "run", side_effect=["1792831184.218\n", listing]):
                scope = runner.begin_logcat_scope(["adb", "-s", "owned"], out)
            self.assertEqual("ready", scope["status"])
            self.assertEqual({10329, 10348}, set(scope["package_uids"].values()))
            with patch.object(runner, "run", return_value="") as capture:
                runner.capture_scoped_logcat(["adb", "-s", "owned"], out, scope)
            self.assertEqual("collected", scope["capture_status"])
            self.assertEqual(
                [
                    "adb",
                    "-s",
                    "owned",
                    "logcat",
                    "-d",
                    "-v",
                    "threadtime",
                    "--uid=10329,10348",
                    "-T",
                    "1792831184.218",
                    "-m",
                    "20000",
                ],
                capture.call_args.args[0],
            )
            self.assertEqual(15, capture.call_args.kwargs["timeout"])
            self.assertEqual(out / "android-logcat.log", capture.call_args.args[1])
            (out / "android-logcat.log").write_text(
                "10-24 12:40:00.000 32100 32101 E AndroidRuntime: FATAL EXCEPTION: owned\n"
                "10-24 12:40:00.001 32100 32101 E         : Failed to start VPN\n"
            )
            summary = runner.runtime_summary(
                out, {"status": "passed", "logcat_scope": scope}
            )
            self.assertIn("process-crash", summary["diagnostic_codes"])
            self.assertEqual("passed", summary["status"])

    def test_logcat_unknown_scope_never_falls_back_to_unscoped_capture(self):
        app = "package:com.poyka.ripdpi uid:10329\n"
        test = "package:com.poyka.ripdpi.test uid:10348\n"
        cases = (
            [RuntimeError("private lookup")],
            ["invalid-time"],
            ["1792831184.218", RuntimeError("private uid")],
            ["1792831184.218", app],
            ["1792831184.218", app + test + test],
            ["1792831184.218", app + test.replace("10348", "10329")],
            ["1792831184.218", app.replace("10329", "0") + test],
        )
        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            for results in cases:
                with (
                    self.subTest(results=results),
                    patch.object(runner, "run", side_effect=results),
                ):
                    scope = runner.begin_logcat_scope(["adb"], out)
                self.assertEqual("unavailable", scope["status"])
                with patch.object(runner, "run") as capture:
                    runner.capture_scoped_logcat(["adb"], out, scope)
                capture.assert_not_called()
                self.assertNotIn("private", str(scope))

    def test_logcat_capture_failure_is_bounded_metadata_and_preserves_verdict(self):
        import json

        with tempfile.TemporaryDirectory() as temporary:
            out = Path(temporary)
            scope = {
                "status": "ready",
                "capture_status": "pending",
                "max_messages": 20000,
                "since_device_epoch": "1792831184.218",
                "package_uids": {
                    "com.poyka.ripdpi": 10329,
                    "com.poyka.ripdpi.test": 10348,
                },
            }
            with patch.object(
                runner, "run", side_effect=RuntimeError("private path token")
            ):
                runner.capture_scoped_logcat(["adb"], out, scope)
            report = {"status": "passed", "logcat_scope": scope}
            summary = runner.runtime_summary(out, report)
            self.assertEqual("passed", summary["status"])
            self.assertEqual("failed", summary["logcat_scope"]["capture_status"])
            self.assertEqual(
                "bounded-not-exhaustive", summary["logcat_scope"]["coverage"]
            )
            self.assertIn("logcat-capture-incomplete", summary["diagnostic_codes"])
            public = json.dumps(summary)
            for private in (
                "private",
                "10329",
                "10348",
                "1792831184",
                "com.poyka.ripdpi",
                temporary,
            ):
                self.assertNotIn(private, public)
            self.assertEqual("capture-failed", scope["reason"])

    def test_main_requires_test_success_before_required_receipts(self):
        import json
        import subprocess

        for status in (-2, 0):
            with (
                tempfile.TemporaryDirectory() as temporary,
                self.subTest(status=status),
            ):
                out = Path(temporary) / "result"
                argv = [
                    "runner",
                    "--scenario",
                    "android-xray",
                    "--run-id",
                    "owned-run",
                    "--out-dir",
                    str(out),
                    "--serial",
                    "emulator-5554",
                    "--prepared-dir",
                    temporary,
                    "--xray-control-port",
                    "41000",
                ]
                outputs = {
                    "source.log": "a" * 40,
                    "devices.log": "emulator-5554\tdevice\n",
                    "device-kind.log": "1",
                    "device-abi.log": "arm64-v8a",
                    "logcat-boundary.log": "1792831184.218",
                    "logcat-packages.log": "package:com.poyka.ripdpi uid:10329\npackage:com.poyka.ripdpi.test uid:10348\n",
                    "instrumentation-components.log": (
                        "instrumentation:com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner "
                        "(target=com.poyka.ripdpi)\n"
                    ),
                    "instrumentation.log": transcript(code=status),
                }

                process_calls = []

                def process_output(_command, path, **_kwargs):
                    process_calls.append(path.name)
                    value = outputs.get(path.name, "")
                    path.write_text(value)
                    return value

                required_reads = []

                def read_receipts(_host, _port, endpoint, *_args):
                    if endpoint == "manifest":
                        return {"runId": "owned-run", "version": "owned-version"}
                    required_reads.append(endpoint)
                    raise ValueError("required receipt is unavailable")

                metadata = {
                    "apks": {
                        kind: {"path": "/owned/" + kind + ".apk", "sha256": "b" * 64}
                        for kind in ("app", "test")
                    }
                }
                with (
                    patch.object(runner.sys, "argv", argv),
                    patch.dict(runner.SCENARIOS, {"android-xray": [TEST]}),
                    patch.object(runner, "source_fingerprint", return_value="c" * 64),
                    patch.object(runner, "prepared", return_value=metadata),
                    patch.object(runner, "run", side_effect=process_output),
                    patch.object(runner, "fetch", side_effect=read_receipts),
                    patch.object(runner.signal, "signal"),
                    patch.object(
                        runner.subprocess,
                        "run",
                        return_value=subprocess.CompletedProcess([], 1, "", ""),
                    ),
                    patch("builtins.print"),
                ):
                    self.assertEqual(1, runner.main())
                report = json.loads((out / "result.json").read_text())
                self.assertTrue(report["cleanup"]["passed"])
                self.assertEqual("collected", report["logcat_scope"]["capture_status"])
                self.assertLess(
                    process_calls.index("install-test.log"),
                    process_calls.index("logcat-boundary.log"),
                )
                self.assertLess(
                    process_calls.index("logcat-boundary.log"),
                    process_calls.index("instrumentation.log"),
                )
                self.assertEqual("failed", report["status"])
                self.assertEqual(
                    transcript(code=status),
                    (out / "test-1/instrumentation.log").read_text(),
                )
                if status == -2:
                    self.assertEqual([], required_reads)
                    self.assertIn("instrumentation failed or skipped", report["error"])
                else:
                    self.assertEqual(["receipts"], required_reads)
                    self.assertEqual("required receipt is unavailable", report["error"])

    def test_owned_runner_preserves_unrelated_instrumentation(self):
        owned = "com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner"
        other = (
            "com.poyka.ripdpi.baselineprofile/androidx.test.runner.AndroidJUnitRunner"
        )
        listing = (
            f"instrumentation:{other} (target=com.poyka.ripdpi)\n"
            f"instrumentation:{owned} (target=com.poyka.ripdpi)\n"
        )
        self.assertEqual(owned, runner.owned_instrumentation_component(listing))

    def test_owned_runner_rejects_missing_wrong_target_or_ambiguous_components(self):
        owned = "instrumentation:com.poyka.ripdpi.test/com.poyka.ripdpi.HiltTestRunner"
        for listing in (
            "",
            "instrumentation:other.test/OtherRunner (target=com.poyka.ripdpi)\n",
            f"{owned} (target=other.app)\n",
            f"{owned} (target=com.poyka.ripdpi)\n" * 2,
        ):
            with self.subTest(listing=listing):
                with self.assertRaises(runner.Blocked):
                    runner.owned_instrumentation_component(listing)

    def test_naiveproxy_scenario_takes_the_binary_the_lab_supplies(self):
        base = [
            "runner",
            "--run-id",
            "naive-test",
            "--out-dir",
            "/tmp/owned",
            "--serial",
            "emulator-5554",
            "--scenario",
            "android-naiveproxy",
        ]
        with patch.object(runner.sys, "argv", base), patch("sys.stderr"):
            with self.assertRaises(SystemExit):
                runner.arguments()
        with patch.object(
            runner.sys, "argv", base + ["--naiveproxy-binary", "/tmp/peer/caddy"]
        ):
            self.assertEqual(Path("/tmp/peer/caddy"), runner.arguments().naiveproxy_binary)

    def test_usb_scope_requires_its_own_named_scenario_and_no_lan_addresses(self):
        base = [
            "runner",
            "--run-id",
            "usb-test",
            "--out-dir",
            "/tmp/owned",
            "--serial",
            "owned-device",
            "--physical-device",
        ]
        valid = base + [
            "--physical-adb-bridge",
            "--scenario",
            "android-usb-transactions",
        ]
        with patch.object(runner.sys, "argv", valid):
            self.assertTrue(runner.arguments().physical_adb_bridge)
        for extra in (
            ["--physical-adb-bridge", "--scenario", "android-xray"],
            ["--scenario", "android-usb-transactions"],
            ["--scenario", "android-transactions"],
            [
                "--physical-adb-bridge",
                "--scenario",
                "android-usb-transactions",
                "--physical-host",
                "192.168.1.2",
            ],
        ):
            with (
                self.subTest(extra=extra),
                patch.object(runner.sys, "argv", base + extra),
                patch("sys.stderr"),
            ):
                with self.assertRaises(SystemExit):
                    runner.arguments()

    def test_usb_summary_excludes_direct_network_claims(self):
        with tempfile.TemporaryDirectory() as temporary:
            report = {
                "scenario_id": "android-usb-transactions",
                "status": "passed",
                "data_scope": "local-usb-transport",
            }
            summary = runner.runtime_summary(Path(temporary), report)
            self.assertEqual("local-usb-transport", summary["data_scope"])
            self.assertIn("underlying-network-bypass", summary["unverified_boundaries"])
            self.assertIn("direct-device-lan-udp", summary["unverified_boundaries"])

    def test_heavy_commands_use_the_machine_gate_on_macos(self):
        with patch.object(runner.sys, "platform", "darwin"):
            self.assertEqual(
                ["build-gate", "--", "go", "test"], runner.heavy_command(["go", "test"])
            )
        with patch.object(runner.sys, "platform", "linux"):
            self.assertEqual(["go", "test"], runner.heavy_command(["go", "test"]))

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

    def test_xray_debug_is_opt_in_and_described_in_help(self):
        from unittest.mock import patch
        import contextlib
        import io

        base = [
            str(SOURCE),
            "--scenario",
            "android-xray",
            "--run-id",
            "debug-test",
            "--out-dir",
            "/tmp/unused-debug-test",
            "--serial",
            "emulator-5584",
        ]
        with patch.object(sys, "argv", base):
            self.assertFalse(runner.arguments().xray_debug)
        with patch.object(sys, "argv", base + ["--xray-debug"]):
            self.assertTrue(runner.arguments().xray_debug)
        output = io.StringIO()
        with (
            patch.object(sys, "argv", [str(SOURCE), "--help"]),
            contextlib.redirect_stdout(output),
        ):
            with self.assertRaises(SystemExit) as result:
                runner.arguments()
        self.assertEqual(0, result.exception.code)
        self.assertIn("--xray-debug", output.getvalue())
        self.assertIn("opt-in", output.getvalue())

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
