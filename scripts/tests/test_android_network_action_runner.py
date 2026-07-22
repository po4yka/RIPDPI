#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import os
import hashlib
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "test-lab/scripts/run-android-network-evidence-action.sh"
VALIDATOR = ROOT / "scripts/ci/check_android_network_action_receipt.py"
ACTION_MAP = ROOT / "quality/release-gates/android-network-evidence-actions.json"
LOCK_HELPER = ROOT / "test-lab/scripts/run-with-android-device-lock.py"
READY_OVERRIDE = (
    ROOT / "scripts/tests/fixtures/android-network-evidence-test-ready-override.json"
)
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


def receipt(gate_id: str = module.GATE_ID) -> dict[str, object]:
    descriptor = module.load_action_registry()[gate_id]
    facts = module.example_valid_facts(gate_id)
    return {
        "version": module.VERSION,
        "status": "PASS",
        "gateId": gate_id,
        "kind": descriptor.kind,
        "selector": descriptor.selector,
        "semanticRule": descriptor.semantic_rule,
        "correlationId": CORRELATION_ID,
        "sourceSha": SOURCE_SHA,
        "clientArtifactSha256": CLIENT_SHA,
        "testArtifactSha256": TEST_SHA,
        "fixtureIdentitySha256": FIXTURE_SHA,
        "actionMarkerSha256": module.marker_sha256(
            CORRELATION_ID, "action", gate_id=gate_id, kind=descriptor.kind
        ),
        "outcomeMarkerSha256": module.marker_sha256(
            CORRELATION_ID, "outcome", gate_id=gate_id, kind=descriptor.kind
        ),
        "querySetSha256": module.query_set_sha256(gate_id, facts),
        "startedAtElapsedRealtimeMs": 100,
        "actionMarkerAtElapsedRealtimeMs": 110,
        "outcomeMarkerAtElapsedRealtimeMs": 300,
        "finishedAtElapsedRealtimeMs": 400,
        "appUid": 10101,
        "testUid": 10102,
        "actionMarkerPid": 201,
        "actionMarkerUid": 10101,
        "outcomeMarkerPid": 201,
        "outcomeMarkerUid": 10101,
        "dnsProbePids": [301],
        "dnsProbeUid": 10102,
        "facts": facts,
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
    source = args[-1]
    is_transcript = "network-evidence-fixture-transcript-" in source
    if mode == "missing_receipt" and not is_transcript:
        raise SystemExit(1)
    if mode == "missing_transcript" and is_transcript:
        raise SystemExit(1)
    artifact = os.environ["FAKE_TRANSCRIPT"] if is_transcript else os.environ["FAKE_RECEIPT"]
    sys.stdout.write(pathlib.Path(artifact).read_text(encoding="utf-8"))
elif len(args) >= 4 and args[:2] == ["shell", "timeout"] and "instrument" in args:
    selector = args[args.index("class") + 1]
    test_class, test_method = selector.split("#", 1)
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
    def wait_for_path(self, path: Path, process: subprocess.Popen[str]) -> None:
        for _ in range(200):
            if path.exists():
                return
            if process.poll() is not None:
                break
            time.sleep(0.02)
        self.fail(f"locked workload did not create {path.name}; exit={process.poll()}")

    def start_locked_workload(
        self,
        root: Path,
        marker: Path,
        *,
        state_path: Path | None = None,
        handshake_hold_path: Path | None = None,
        workload: str | None = None,
    ) -> subprocess.Popen[str]:
        workload = workload or (
            "import pathlib,signal,sys,time; "
            "signal.signal(signal.SIGTERM, lambda *_: sys.exit(0)); "
            "pathlib.Path(sys.argv[1]).write_text(str(__import__('os').getpid())); "
            "time.sleep(60)"
        )
        command = [
            sys.executable,
            str(LOCK_HELPER),
            "run",
            "--lock-root",
            str(root),
            "--lock-name",
            "device.lock",
        ]
        if state_path is not None:
            command.extend(["--state-path", str(state_path)])
        if handshake_hold_path is not None:
            command.extend(["--handshake-hold-path", str(handshake_hold_path)])
        command.extend([sys.executable, "-c", workload, str(marker)])
        return subprocess.Popen(
            command,
            text=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )

    def test_device_lock_excludes_live_owner_and_recovers_after_exit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first_marker = root / "first.marker"
            first = self.start_locked_workload(root, first_marker)
            self.wait_for_path(first_marker, first)
            second_marker = root / "second.marker"
            second = self.start_locked_workload(root, second_marker)
            self.assertEqual(second.wait(timeout=2), 3)
            self.assertFalse(second_marker.exists())
            first.terminate()
            self.assertEqual(first.wait(timeout=2), -signal.SIGTERM)
            third_marker = root / "third.marker"
            third = self.start_locked_workload(root, third_marker)
            self.wait_for_path(third_marker, third)
            third.terminate()
            self.assertEqual(third.wait(timeout=2), -signal.SIGTERM)

    def test_device_lock_metadata_remains_valid_across_reuse(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            command = [
                sys.executable,
                str(LOCK_HELPER),
                "run",
                "--lock-root",
                str(root),
                "--lock-name",
                "device.lock",
                sys.executable,
                "-c",
                "pass",
            ]
            for _ in range(3):
                self.assertEqual(subprocess.run(command, check=False).returncode, 0)
                metadata = json.loads(
                    (root / "device.lock").read_text(encoding="utf-8")
                )
                self.assertRegex(metadata["nonce"], r"^[0-9a-f]{64}$")

    def test_unlink_and_replace_aborts_owner_before_second_workload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first_marker = root / "first.marker"
            first = self.start_locked_workload(root, first_marker)
            self.wait_for_path(first_marker, first)
            lock_path = root / "device.lock"
            lock_path.unlink()
            lock_path.write_text("replacement", encoding="utf-8")
            lock_path.chmod(0o600)
            second_marker = root / "second.marker"
            second = self.start_locked_workload(root, second_marker)
            self.assertEqual(first.wait(timeout=2), 4)
            self.assertNotEqual(second.wait(timeout=2), 0)
            self.assertFalse(second_marker.exists())
            lock_path.unlink()
            third_marker = root / "third.marker"
            third = self.start_locked_workload(root, third_marker)
            self.wait_for_path(third_marker, third)
            third.terminate()
            self.assertEqual(third.wait(timeout=2), -signal.SIGTERM)

    def test_sigkill_of_fd_holder_aborts_workload_and_releases_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first_marker = root / "first.marker"
            first = self.start_locked_workload(root, first_marker)
            self.wait_for_path(first_marker, first)
            workload_pid = int(first_marker.read_text(encoding="utf-8"))
            os.kill(first.pid, signal.SIGKILL)
            self.assertEqual(first.wait(timeout=2), -signal.SIGKILL)
            self.assert_process_exits(
                workload_pid, "workload survived supervisor SIGKILL"
            )
            second_marker = root / "second.marker"
            second = self.start_locked_workload(root, second_marker)
            self.wait_for_path(second_marker, second)
            second.terminate()
            self.assertEqual(second.wait(timeout=2), -signal.SIGTERM)

    def assert_process_exits(self, pid: int, message: str) -> None:
        for _ in range(100):
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            time.sleep(0.02)
        self.fail(message)

    def test_guard_sigkill_aborts_process_group_and_releases_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state_path = root / "state.json"
            marker = root / "workload.marker"
            supervisor = self.start_locked_workload(root, marker, state_path=state_path)
            self.wait_for_path(marker, supervisor)
            state = json.loads(state_path.read_text(encoding="utf-8"))
            workload_pid = int(marker.read_text(encoding="utf-8"))
            os.kill(state["guardPid"], signal.SIGKILL)
            self.assertNotEqual(supervisor.wait(timeout=2), 0)
            self.assert_process_exits(workload_pid, "workload survived guard SIGKILL")
            replacement = self.start_locked_workload(root, root / "replacement.marker")
            self.wait_for_path(root / "replacement.marker", replacement)
            replacement.terminate()
            self.assertEqual(replacement.wait(timeout=2), -signal.SIGTERM)

    def test_pre_ack_guard_death_does_not_launch_workload(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state_path = root / "state.json"
            hold_path = root / "hold"
            hold_path.touch()
            marker = root / "workload.marker"
            supervisor = self.start_locked_workload(
                root,
                marker,
                state_path=state_path,
                handshake_hold_path=hold_path,
            )
            self.wait_for_path(state_path, supervisor)
            state = json.loads(state_path.read_text(encoding="utf-8"))
            os.kill(state["guardPid"], signal.SIGKILL)
            self.assertNotEqual(supervisor.wait(timeout=2), 0)
            self.assertFalse(marker.exists())
            hold_path.unlink()
            replacement = self.start_locked_workload(root, root / "replacement.marker")
            self.wait_for_path(root / "replacement.marker", replacement)
            replacement.terminate()
            self.assertEqual(replacement.wait(timeout=2), -signal.SIGTERM)

    def test_stopped_guard_retains_lock_after_supervisor_sigkill(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            state_path = root / "state.json"
            marker = root / "workload.marker"
            supervisor = self.start_locked_workload(root, marker, state_path=state_path)
            self.wait_for_path(marker, supervisor)
            state = json.loads(state_path.read_text(encoding="utf-8"))
            workload_pid = int(marker.read_text(encoding="utf-8"))
            os.kill(state["guardPid"], signal.SIGSTOP)
            os.kill(supervisor.pid, signal.SIGKILL)
            self.assertEqual(supervisor.wait(timeout=2), -signal.SIGKILL)
            blocked_marker = root / "blocked.marker"
            blocked = self.start_locked_workload(root, blocked_marker)
            self.assertEqual(blocked.wait(timeout=2), 3)
            self.assertFalse(blocked_marker.exists())
            os.kill(state["guardPid"], signal.SIGCONT)
            self.assert_process_exits(
                workload_pid, "stopped guard did not clean workload after resume"
            )
            replacement = self.start_locked_workload(root, root / "replacement.marker")
            self.wait_for_path(root / "replacement.marker", replacement)
            replacement.terminate()
            self.assertEqual(replacement.wait(timeout=2), -signal.SIGTERM)

    def test_nominal_exit_kills_background_descendants_without_fd_leak(self) -> None:
        workload = (
            "import pathlib,subprocess,sys; "
            "child=subprocess.Popen(['sleep','60']); "
            "pathlib.Path(sys.argv[1]).write_text(str(child.pid))"
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            marker = root / "child.marker"
            supervisor = self.start_locked_workload(root, marker, workload=workload)
            self.wait_for_path(marker, supervisor)
            child_pid = int(marker.read_text(encoding="utf-8"))
            self.assertEqual(supervisor.wait(timeout=2), 0)
            self.assert_process_exits(
                child_pid, "background descendant survived nominal workload exit"
            )
            replacement = self.start_locked_workload(root, root / "replacement.marker")
            self.wait_for_path(root / "replacement.marker", replacement)
            replacement.terminate()
            self.assertEqual(replacement.wait(timeout=2), -signal.SIGTERM)

    def test_forged_auth_environment_cannot_verify_an_unheld_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            lock = root / "device.lock"
            lock.write_text("{}", encoding="utf-8")
            lock.chmod(0o600)
            result = subprocess.run(
                [
                    sys.executable,
                    str(LOCK_HELPER),
                    "verify",
                    "--lock-root",
                    str(root),
                    "--lock-name",
                    "device.lock",
                    "--supervisor-pid",
                    str(os.getpid()),
                    "--nonce",
                    "forged",
                ],
                env={**os.environ, "RIPDPI_ANDROID_DEVICE_LOCK_AUTH": "forged"},
                capture_output=True,
                text=True,
            )
            self.assertNotEqual(result.returncode, 0)

    def test_action_map_keeps_first_gate_fail_closed(self) -> None:
        action_map = json.loads(ACTION_MAP.read_text(encoding="utf-8"))
        self.assertEqual(action_map["version"], "android_network_evidence_actions_v2")
        self.assertEqual(len(action_map["actions"]), 10)
        action = next(
            item for item in action_map["actions"] if item["gateId"] == module.GATE_ID
        )
        self.assertEqual(action["gateId"], module.GATE_ID)
        self.assertEqual(action["kind"], module.KIND)
        self.assertEqual(action["selector"], module.SELECTOR)
        self.assertEqual(
            action["receiptVersion"], "android_network_evidence_action_receipt_v3"
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
        fixture_transcript_output: Path | None = None,
        gate_id: str = module.GATE_ID,
        test_only_ready_override: Path | None = None,
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
            value = receipt(gate_id)
            transcript = json.dumps(
                {
                    "version": "network_evidence_fixture_transcript_v2",
                    "gateId": gate_id,
                    "events": [],
                },
                separators=(",", ":"),
            ).encode("utf-8")
            if "fixtureTranscriptSha256" in value["facts"]:
                value["facts"]["fixtureTranscriptSha256"] = hashlib.sha256(
                    transcript
                ).hexdigest()
                value["querySetSha256"] = module.query_set_sha256(
                    gate_id, value["facts"]
                )
            value["clientArtifactSha256"] = client_sha
            value["testArtifactSha256"] = test_sha
            if tamper_receipt:
                value["facts"]["preReadyDnsEventCount"] = 1
            private_receipt = root / "device-receipt.json"
            private_receipt.write_text(json.dumps(value), encoding="utf-8")
            private_receipt.chmod(0o600)
            private_transcript = root / "device-transcript.json"
            private_transcript.write_bytes(transcript)
            private_transcript.chmod(0o600)
            output = receipt_output or root / "validated-receipt.json"
            transcript_output = fixture_transcript_output
            if "fixtureTranscriptSha256" in value["facts"] and transcript_output is None:
                transcript_output = root / "validated-transcript.json"
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
                    "FAKE_TRANSCRIPT": str(private_transcript),
                    "FAKE_SOURCE_ROOT": str(ROOT),
                    "FAKE_SOURCE_SHA": SOURCE_SHA,
                    "FAKE_CLIENT_APK_HEX": client_apk.hex(),
                    "FAKE_TEST_APK_HEX": test_apk.hex(),
                    "RIPDPI_ANDROID_DEVICE_LOCK_ROOT": str(root / "device-locks"),
                }
            )
            command = [
                "bash",
                str(RUNNER),
                "--gate-id",
                gate_id,
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
            ]
            if transcript_output is not None:
                command.extend(
                    ["--fixture-transcript-output", str(transcript_output)]
                )
            if test_only_ready_override is not None:
                command.extend(
                    [
                        "--test-only-action-registry-override",
                        str(test_only_ready_override),
                    ]
                )
            result = subprocess.run(
                command,
                cwd=ROOT,
                env=environment,
                text=True,
                capture_output=True,
                timeout=15,
            )
            if result.returncode == 0:
                self.assertEqual(json.loads(output.read_text(encoding="utf-8")), value)
                self.assertEqual(output.stat().st_mode & 0o777, 0o600)
                if transcript_output is not None:
                    self.assertEqual(transcript_output.read_bytes(), transcript)
                    self.assertEqual(transcript_output.stat().st_mode & 0o777, 0o600)
            return result

    def test_non_production_ready_descriptor_fails_before_adb(self) -> None:
        result = self.run_runner()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("action descriptor is not production ready", result.stderr)

    def test_source_owned_ready_override_enables_local_action_run(self) -> None:
        result = self.run_runner(test_only_ready_override=READY_OVERRIDE)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Android network evidence action passed", result.stdout)

    def test_source_owned_action_pulls_and_publishes_private_transcript(self) -> None:
        gate_id = "dns-virtual-vpn-resolver"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            receipt_output = root / "receipt.json"
            transcript_output = root / "transcript.json"
            result = self.run_runner(
                gate_id=gate_id,
                test_only_ready_override=READY_OVERRIDE,
                receipt_output=receipt_output,
                fixture_transcript_output=transcript_output,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertTrue(receipt_output.is_file())
            self.assertTrue(transcript_output.is_file())

    def test_source_owned_action_rejects_missing_transcript(self) -> None:
        result = self.run_runner(
            mode="missing_transcript",
            gate_id="dns-virtual-vpn-resolver",
            test_only_ready_override=READY_OVERRIDE,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("fixture transcript readback failed", result.stderr)

    def test_source_owned_action_rejects_output_alias_before_adb(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "shared.json"
            result = self.run_runner(
                gate_id="dns-virtual-vpn-resolver",
                test_only_ready_override=READY_OVERRIDE,
                receipt_output=output,
                fixture_transcript_output=output,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("outputs must be distinct", result.stderr)
            self.assertFalse(output.exists())

    def test_source_owned_action_cleans_partial_pair_publication(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            transcript_output = root / "transcript.json"
            receipt_output = root / "missing" / "receipt.json"
            result = self.run_runner(
                gate_id="dns-virtual-vpn-resolver",
                test_only_ready_override=READY_OVERRIDE,
                receipt_output=receipt_output,
                fixture_transcript_output=transcript_output,
            )
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("could not publish private action outputs", result.stderr)
            self.assertFalse(receipt_output.exists())
            self.assertFalse(transcript_output.exists())

    def test_every_registry_descriptor_runs_only_its_bound_selector(self) -> None:
        for gate_id in module.load_action_registry():
            with self.subTest(gate_id=gate_id):
                result = self.run_runner(gate_id=gate_id)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(
                    "action descriptor is not production ready", result.stderr
                )

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
