#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "test-lab/scripts/run-android-network-evidence-action.sh"
VALIDATOR = ROOT / "scripts/ci/check_android_network_action_receipt.py"
ACTION_MAP = ROOT / "quality/release-gates/android-network-evidence-actions.json"
SPEC = importlib.util.spec_from_file_location(
    "android_network_action_receipt", VALIDATOR
)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)

SOURCE_SHA = "1" * 40
CORRELATION_ID = "2" * 64
CLIENT_SHA = "3" * 64
TEST_SHA = "4" * 64
FIXTURE_SHA = "5" * 64


def receipt() -> dict[str, object]:
    return {
        "version": module.VERSION,
        "status": "PASS",
        "gateId": module.GATE_ID,
        "kind": module.KIND,
        "selector": module.SELECTOR,
        "correlationId": CORRELATION_ID,
        "sourceSha": SOURCE_SHA,
        "clientArtifactSha256": CLIENT_SHA,
        "testArtifactSha256": TEST_SHA,
        "fixtureIdentitySha256": FIXTURE_SHA,
        "actionMarkerSha256": module.marker_sha256(CORRELATION_ID, "action"),
        "outcomeMarkerSha256": module.marker_sha256(CORRELATION_ID, "outcome"),
        "startedAtElapsedRealtimeMs": 100,
        "actionMarkerAtElapsedRealtimeMs": 110,
        "outcomeMarkerAtElapsedRealtimeMs": 300,
        "finishedAtElapsedRealtimeMs": 400,
        "appAndTestUidsDistinct": True,
        "actionMarkerRanAsTargetApp": True,
        "outcomeMarkerRanAsTargetApp": True,
        "dnsProbeRanAsAndroidTest": True,
        "actionMarkerPidObserved": True,
        "outcomeMarkerPidObserved": True,
        "dnsProbePidObserved": True,
        "tunFdObserved": True,
        "closedWindowRunningCount": 0,
        "preReadyDnsEventCount": 0,
        "startupWindowAssertionElapsedMs": 300,
        "dnsRcode": 0,
        "dnsAnswersExact": True,
        "postReadyDnsEventCount": 1,
        "txPackets": 2,
        "rxPackets": 1,
        "finalStatus": "Halted",
        "gateClean": True,
    }


FAKE_ADB = r"""#!/usr/bin/env python3
import os
import pathlib
import sys

args = sys.argv[1:]
if args[:2] == ["-s", "pixel"]:
    args = args[2:]
mode = os.environ.get("FAKE_ADB_MODE", "pass")
if args == ["get-state"]:
    print("device")
elif args[:3] == ["shell", "getprop", "ro.kernel.qemu"]:
    print("1" if mode == "emulator" else "0")
elif args[:3] == ["shell", "getprop", "ro.boot.qemu"]:
    print("0")
elif args[:3] == ["shell", "getprop", "ro.hardware"]:
    print("gs201")
elif args[:4] == ["shell", "pm", "path", "com.poyka.ripdpi"]:
    print("package:/data/app/com.poyka.ripdpi/base.apk")
elif args[:4] == ["shell", "pm", "path", "com.poyka.ripdpi.test"]:
    if mode == "two_test_apks":
        print("package:/data/app/com.poyka.ripdpi.test/base.apk")
        print("package:/data/app/com.poyka.ripdpi.test/split.apk")
    else:
        print("package:/data/app/com.poyka.ripdpi.test/base.apk")
elif args[:4] == ["shell", "pm", "list", "instrumentation"]:
    print("instrumentation:com.poyka.ripdpi.test/androidx.test.runner.AndroidJUnitRunner (target=com.poyka.ripdpi)")
    if mode == "two_components":
        print("instrumentation:com.poyka.ripdpi.extra/androidx.test.runner.AndroidJUnitRunner (target=com.poyka.ripdpi)")
elif len(args) >= 5 and args[:4] == ["shell", "run-as", "com.poyka.ripdpi", "rm"]:
    pass
elif len(args) >= 6 and args[:4] == ["shell", "run-as", "com.poyka.ripdpi", "test"]:
    raise SystemExit(0 if mode == "stale_receipt" else 1)
elif len(args) >= 5 and args[:4] == ["shell", "run-as", "com.poyka.ripdpi", "stat"]:
    print("644" if mode == "mode" else "600")
elif len(args) >= 5 and args[:4] == ["shell", "run-as", "com.poyka.ripdpi", "cat"]:
    if mode == "missing_receipt":
        raise SystemExit(1)
    sys.stdout.write(pathlib.Path(os.environ["FAKE_RECEIPT"]).read_text(encoding="utf-8"))
elif len(args) >= 4 and args[:2] == ["shell", "timeout"] and "instrument" in args:
    test_class = "com.poyka.ripdpi.e2e.VpnStartupWindowE2ETest"
    test_method = "vpnStartupWindowHoldsDnsPacketUntilNativeReady"
    if mode == "skip":
        print("INSTRUMENTATION_STATUS: numtests=0")
        print("INSTRUMENTATION_STATUS: stream=assumption failed")
        print("OK (0 tests)")
        print("INSTRUMENTATION_CODE: -1")
    elif mode == "two_tests":
        for code in (1, 0):
            print(f"INSTRUMENTATION_STATUS: class={test_class}")
            print("INSTRUMENTATION_STATUS: numtests=2")
            print(f"INSTRUMENTATION_STATUS: test={test_method}")
            print(f"INSTRUMENTATION_STATUS_CODE: {code}")
        print("OK (2 tests)")
        print("INSTRUMENTATION_CODE: -1")
    elif mode == "crash":
        print("INSTRUMENTATION_STATUS: shortMsg=Process crashed.")
        print("INSTRUMENTATION_CODE: 0")
    elif mode == "timeout":
        raise SystemExit(124)
    else:
        for code in (1, 0):
            print(f"INSTRUMENTATION_STATUS: class={test_class}")
            print("INSTRUMENTATION_STATUS: numtests=1")
            print(f"INSTRUMENTATION_STATUS: test={test_method}")
            print(f"INSTRUMENTATION_STATUS_CODE: {code}")
        print("OK (1 test)")
        print("INSTRUMENTATION_CODE: -1")
elif len(args) == 3 and args[0] == "pull":
    destination = pathlib.Path(args[2])
    if "com.poyka.ripdpi.test" in args[1]:
        destination.write_bytes(bytes.fromhex(os.environ["FAKE_TEST_APK_HEX"]))
    else:
        destination.write_bytes(bytes.fromhex(os.environ["FAKE_CLIENT_APK_HEX"]))
else:
    print(f"unexpected fake adb arguments: {args!r}", file=sys.stderr)
    raise SystemExit(97)
"""

FAKE_GIT = r"""#!/usr/bin/env python3
import os
import sys

args = sys.argv[1:]
if "rev-parse" in args and "--show-toplevel" in args:
    print(os.environ["FAKE_SOURCE_ROOT"])
elif "rev-parse" in args and "HEAD" in args:
    print(os.environ["FAKE_SOURCE_SHA"])
elif "status" in args:
    pass
else:
    raise SystemExit(97)
"""


class AndroidNetworkActionRunnerTest(unittest.TestCase):
    def test_action_map_keeps_first_gate_fail_closed(self) -> None:
        action_map = json.loads(ACTION_MAP.read_text(encoding="utf-8"))
        self.assertEqual(action_map["version"], "android_network_evidence_actions_v1")
        self.assertEqual(len(action_map["actions"]), 1)
        action = action_map["actions"][0]
        self.assertEqual(action["gateId"], module.GATE_ID)
        self.assertEqual(action["kind"], module.KIND)
        self.assertEqual(action["selector"], module.SELECTOR)
        self.assertEqual(
            action["receiptVersion"], "android_network_evidence_action_receipt_v1"
        )
        self.assertEqual(action["semanticRule"], "tun-establish-native-ready-v1")
        self.assertEqual(
            action["windowPolicy"],
            "conservative-superset-before-vpn-start-through-running",
        )
        self.assertIs(action["productionReady"], False)
        self.assertGreaterEqual(len(action["blockingReasons"]), 2)

    def run_runner(
        self,
        mode: str = "pass",
        *,
        tamper_receipt: bool = False,
        receipt_output: Path | None = None,
    ) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            adb = root / "adb"
            adb.write_text(FAKE_ADB, encoding="utf-8")
            adb.chmod(0o700)
            git = root / "git"
            git.write_text(FAKE_GIT, encoding="utf-8")
            git.chmod(0o700)
            client_apk = b"client-apk"
            test_apk = b"test-apk"
            client_sha = hashlib.sha256(client_apk).hexdigest()
            test_sha = hashlib.sha256(test_apk).hexdigest()
            value = receipt()
            value["clientArtifactSha256"] = client_sha
            value["testArtifactSha256"] = test_sha
            if tamper_receipt:
                value["preReadyDnsEventCount"] = 1
            private_receipt = root / "device-receipt.json"
            private_receipt.write_text(json.dumps(value), encoding="utf-8")
            private_receipt.chmod(0o600)
            output = receipt_output or root / "validated-receipt.json"
            environment = os.environ.copy()
            environment.update(
                {
                    "ADB_BIN": str(adb),
                    "GIT_BIN": str(git),
                    "ANDROID_SERIAL": "pixel",
                    "RIPDPI_FIXTURE_ANDROID_HOST": "192.0.2.10",
                    "RIPDPI_FIXTURE_CONTROL_PORT": "8080",
                    "FAKE_ADB_MODE": mode,
                    "FAKE_RECEIPT": str(private_receipt),
                    "FAKE_SOURCE_ROOT": str(ROOT),
                    "FAKE_SOURCE_SHA": SOURCE_SHA,
                    "FAKE_CLIENT_APK_HEX": client_apk.hex(),
                    "FAKE_TEST_APK_HEX": test_apk.hex(),
                }
            )
            result = subprocess.run(
                [
                    "bash",
                    str(RUNNER),
                    "--gate-id",
                    module.GATE_ID,
                    "--correlation-id",
                    CORRELATION_ID,
                    "--source-sha",
                    SOURCE_SHA,
                    "--client-artifact-sha256",
                    client_sha,
                    "--test-artifact-sha256",
                    test_sha,
                    "--fixture-identity-sha256",
                    FIXTURE_SHA,
                    "--receipt-output",
                    str(output),
                ],
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=15,
            )
            if result.returncode == 0:
                self.assertEqual(json.loads(output.read_text(encoding="utf-8")), value)
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            return result

    def test_exact_single_test_and_private_receipt_pass(self) -> None:
        result = self.run_runner()
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_skip_or_zero_tests_fails(self) -> None:
        result = self.run_runner("skip")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("skipped, failed, incomplete, or ambiguous", result.stderr)

    def test_two_tests_fails(self) -> None:
        result = self.run_runner("two_tests")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("skipped, failed, incomplete, or ambiguous", result.stderr)

    def test_crash_fails(self) -> None:
        result = self.run_runner("crash")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("skipped, failed, incomplete, or ambiguous", result.stderr)

    def test_instrumentation_timeout_fails(self) -> None:
        result = self.run_runner("timeout")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("instrumentation command failed", result.stderr)

    def test_ambiguous_instrumentation_component_fails(self) -> None:
        result = self.run_runner("two_components")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "expected exactly one RIPDPI instrumentation component", result.stderr
        )

    def test_emulator_fails_before_instrumentation(self) -> None:
        result = self.run_runner("emulator")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("requires a physical Android device", result.stderr)

    def test_ambiguous_installed_test_apk_fails(self) -> None:
        result = self.run_runner("two_test_apks")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("expected exactly one installed APK path", result.stderr)

    def test_stale_receipt_surviving_pre_clear_fails(self) -> None:
        result = self.run_runner("stale_receipt")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "stale action receipt remained before instrumentation", result.stderr
        )

    def test_malformed_fixture_host_fails_before_adb(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            output = root / "validated-receipt.json"
            result = subprocess.run(
                [
                    "bash",
                    str(RUNNER),
                    "--gate-id",
                    module.GATE_ID,
                    "--correlation-id",
                    CORRELATION_ID,
                    "--source-sha",
                    SOURCE_SHA,
                    "--client-artifact-sha256",
                    CLIENT_SHA,
                    "--test-artifact-sha256",
                    TEST_SHA,
                    "--fixture-identity-sha256",
                    FIXTURE_SHA,
                    "--receipt-output",
                    str(output),
                ],
                cwd=ROOT,
                env={
                    **os.environ,
                    "ADB_BIN": "/bin/false",
                    "GIT_BIN": "/bin/false",
                    "ANDROID_SERIAL": "pixel",
                    "RIPDPI_FIXTURE_ANDROID_HOST": "127.0.0.1",
                    "RIPDPI_FIXTURE_CONTROL_PORT": "8080",
                },
                text=True,
                capture_output=True,
                timeout=15,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("numeric routed unicast address", result.stderr)

    def test_non_private_device_receipt_fails(self) -> None:
        result = self.run_runner("mode")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("mode must be 600", result.stderr)

    def test_missing_receipt_fails(self) -> None:
        result = self.run_runner("missing_receipt")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("action receipt readback failed", result.stderr)

    def test_semantically_tampered_receipt_fails(self) -> None:
        result = self.run_runner(tamper_receipt=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("missing, partial, or malformed", result.stderr)

    def test_existing_receipt_output_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "validated-receipt.json"
            output.write_text("stale", encoding="utf-8")
            result = self.run_runner(receipt_output=output)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(output.read_text(encoding="utf-8"), "stale")

    def test_receipt_output_symlink_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "target.json"
            target.write_text("stale", encoding="utf-8")
            output = root / "validated-receipt.json"
            output.symlink_to(target)
            result = self.run_runner(receipt_output=output)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(target.read_text(encoding="utf-8"), "stale")


if __name__ == "__main__":
    unittest.main()
