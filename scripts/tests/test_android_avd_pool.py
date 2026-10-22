#!/usr/bin/env python3
"""Local AVD lifecycle contract tests with simulated installed SDK tools."""

from __future__ import annotations

import importlib.util
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from unittest import mock
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/ci/android-avd-pool.py"
SPEC = importlib.util.spec_from_file_location("android_avd_pool", SCRIPT)
POOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(POOL)

FAKE_TOOL = r'''
import json, os, signal, socket, sys, time, xml.etree.ElementTree as ET
from pathlib import Path
kind = Path(sys.argv[0]).name
args = sys.argv[1:]
state = Path(os.environ["FAKE_STATE"])
with (state / "commands").open("a") as log:
    log.write(json.dumps([kind, *args]) + "\n")
if kind == "ps":
    print(os.environ.get("FAKE_PS", ""))
elif kind == "lsof":
    sys.exit(int(os.environ.get("FAKE_LSOF", "1")))
elif kind == "avdmanager":
    name = args[args.index("-n") + 1]
    directory = Path(args[args.index("-p") + 1])
    package = args[args.index("-k") + 1].split(";")
    image = Path(os.environ["ANDROID_SDK_ROOT"]).joinpath(*package)
    details = ET.parse(image / "package.xml").getroot().find("{*}localPackage/{*}type-details")
    tags = [tag.findtext("{*}id") for tag in details.findall("{*}tag")]
    profile = args[args.index("-d") + 1]
    directory.mkdir()
    (directory / "config.ini").write_text(
        f"abi.type={package[3]}\ntag.id={tags[0]}\ntag.ids={','.join(tags)}\nhw.device.name={profile}\n"
        f"image.sysdir.1=system-images/{package[1]}/{package[2]}/{package[3]}/\n")
    (directory.parent / f"{name}.ini").write_text(
        f"path={directory}\ntarget={package[1]}\n")
    sys.exit(int(os.environ.get("FAKE_CREATE_EXIT", "0")))
elif kind == "emulator":
    name = args[args.index("-avd") + 1]
    port = int(args[args.index("-port") + 1])
    sockets = []
    for number in (port, port + 1):
        sock = socket.socket()
        sock.bind(("127.0.0.1", number))
        sock.listen()
        sockets.append(sock)
    marker = state / f"device-{port}"
    marker.write_text(name)
    pid_file = state / f"pid-{port}"
    pid_file.write_text(str(os.getpid()))
    def stop(*_):
        marker.unlink(missing_ok=True)
        pid_file.unlink(missing_ok=True)
        sys.exit(0)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    if os.environ.get("FAKE_EMULATOR_EXIT"):
        stop()
    lifetime = float(os.environ.get("FAKE_EMULATOR_LIFETIME", "0"))
    deadline = time.monotonic() + lifetime
    while True:
        if lifetime and time.monotonic() >= deadline:
            stop()
        time.sleep(.1)
elif kind == "adb":
    if args == ["devices"]:
        print("List of devices attached")
        for path in state.glob("device-*"):
            print(f"emulator-{path.name[7:]}\tdevice")
        if os.environ.get("FAKE_OFFLINE"):
            print("emulator-5690\toffline")
    else:
        assert args[0] == "-s", args
        marker = state / args[1].replace("emulator-", "device-")
        if not marker.exists():
            sys.exit(1)
        if args[2:] == ["emu", "avd", "name"]:
            print(marker.read_text() + "\nOK")
        elif args[2:] in (["shell", "getprop", "sys.boot_completed"],
                          ["shell", "getprop", "dev.bootcomplete"]):
            timeout_flag = state / "first-boot-probe-timeout"
            if (args[-1] == "sys.boot_completed" and os.environ.get("FAKE_FIRST_BOOT_TIMEOUT")
                    and not timeout_flag.exists()):
                timeout_flag.write_text("yes")
                time.sleep(6)
            print("0" if os.environ.get("FAKE_NO_BOOT") else "1")
        elif args[2:] == ["shell", "pm", "path", "android"]:
            print("package:/system/framework/framework-res.apk")
        else:
            sys.exit(1)
'''


class AvdPoolTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name).resolve()
        self.sdk = self.root / "sdk"
        self.home = self.root / "avd"
        self.state = self.root / "fake-state"
        self.state.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        locations = [self.sdk / "platform-tools/adb", self.sdk / "emulator/emulator",
                     self.sdk / "cmdline-tools/latest/bin/avdmanager", self.bin / "ps", self.bin / "lsof"]
        for path in locations:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"#!{sys.executable}\n" + FAKE_TOOL)
            path.chmod(0o755)
        self.install_image()
        self.env = dict(os.environ, ANDROID_SDK_ROOT=str(self.sdk), ANDROID_AVD_HOME=str(self.home),
                        FAKE_STATE=str(self.state), PATH=f"{self.bin}:{os.environ['PATH']}")
        self.base = ["run", "--api", "37", "--arch", "arm64-v8a", "--boot-timeout", "5"]

    def tearDown(self):
        self.temporary.cleanup()

    def install_image(self, target="google_apis", tags=("google_apis",)):
        package_id = f"system-images;android-37.0;{target};arm64-v8a"
        image = self.sdk.joinpath(*package_id.split(";"))
        image.mkdir(parents=True, exist_ok=True)
        (image / "source.properties").write_text("Pkg.Revision=1\n")
        root = ET.Element("repository")
        package = ET.SubElement(root, "localPackage", path=package_id)
        details = ET.SubElement(package, "type-details")
        ET.SubElement(details, "api-level").text = "37.0"
        ET.SubElement(details, "abi").text = "arm64-v8a"
        for tag in tags:
            ET.SubElement(ET.SubElement(details, "tag"), "id").text = tag
        ET.ElementTree(root).write(image / "package.xml")
        return image

    def invoke(self, *args, **env):
        return subprocess.run([sys.executable, str(SCRIPT), *args], env=dict(self.env, **env),
                              text=True, capture_output=True, timeout=30)

    def run_child(self, code="pass", *flags, **env):
        return self.invoke(*self.base, *flags, "--", sys.executable, "-c", code, **env)

    def commands(self):
        path = self.state / "commands"
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def assert_ok(self, result):
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def ready(self):
        self.assert_ok(self.run_child())

    def test_reuses_compatible_slot_without_creation_or_wipe(self):
        self.ready()
        marker = self.home / "RIPDPI_Pool_1.avd/user-data"
        marker.write_text("keep")
        self.assert_ok(self.run_child())
        creates = [cmd for cmd in self.commands() if cmd[0] == "avdmanager"]
        self.assertEqual(1, len(creates))
        self.assertNotIn("-f", creates[0])
        self.assertEqual("keep", marker.read_text())
        self.assertFalse((self.home / "RIPDPI_Pool_2.avd").exists())

    def test_multitag_image_uses_exact_package_and_preserves_all_tags(self):
        self.install_image("google_apis_ps16k", ("google_apis", "page_size_16kb"))
        flags = ("--target", "google_apis_ps16k")
        self.assert_ok(self.run_child("pass", *flags))
        self.assert_ok(self.run_child("pass", *flags))
        creates = [cmd for cmd in self.commands() if cmd[0] == "avdmanager"]
        self.assertEqual(1, len(creates))
        self.assertEqual("system-images;android-37.0;google_apis_ps16k;arm64-v8a",
                         creates[0][creates[0].index("-k") + 1])
        config = self.home / "RIPDPI_Pool_1.avd/config.ini"
        original = config.read_text()
        for changed in (original.replace("tag.id=google_apis", "tag.id=page_size_16kb"),
                        original.replace("tag.ids=google_apis,page_size_16kb", "tag.ids=google_apis")):
            with self.subTest(config=changed):
                config.write_text(changed)
                result = self.run_child("pass", *flags, "--pool-size", "1")
                self.assertEqual(1, result.returncode)
                self.assertIn("incompatible", result.stderr)
                self.assertEqual(changed, config.read_text())

    def test_installed_package_mismatch_is_rejected_before_creation(self):
        image = self.install_image()
        package_file = image / "package.xml"
        original = package_file.read_text()
        for changed in (original.replace("system-images;android-37.0;google_apis;arm64-v8a",
                                         "system-images;android-37.0;google_apis_ps16k;arm64-v8a"),
                        original.replace("<api-level>37.0", "<api-level>35"),
                        original.replace("<abi>arm64-v8a", "<abi>x86_64"),
                        original.replace("<id>google_apis</id>", "<id></id>"),
                        "<broken"):
            with self.subTest(package=changed):
                package_file.write_text(changed)
                result = self.run_child()
                self.assertEqual(1, result.returncode)
                self.assertFalse(any(cmd[0] == "avdmanager" for cmd in self.commands()))
                self.assertFalse(list(self.home.glob("*.avd")))

    def test_exports_unique_target_serial_and_cold_boot_flags(self):
        self.assert_ok(self.run_child("import os; print(os.environ['ANDROID_SERIAL'], os.environ['RIPDPI_AVD_NAME'])"))
        calls = self.commands()
        emulator = next(cmd for cmd in calls if cmd[0] == "emulator")
        port = emulator[emulator.index("-port") + 1]
        self.assertEqual(0, int(port) % 2)
        self.assertIn("-no-snapshot", emulator)
        self.assertIn("-no-snapshot-save", emulator)
        self.assertEqual("host" if sys.platform == "darwin" else "swiftshader_indirect",
                         emulator[emulator.index("-gpu") + 1])
        for adb in [cmd for cmd in calls if cmd[0] == "adb" and cmd[1:] != ["devices"]]:
            self.assertEqual(["-s", f"emulator-{port}"], adb[1:3])

    def test_full_pool_mismatch_is_preserved_and_explicit_cleanup_unlocks_profile(self):
        self.ready()
        self.assert_ok(self.run_child("pass", "--profile", "pixel_10_pro_xl"))
        result = self.run_child("pass", "--profile", "pixel_6")
        self.assertEqual(1, result.returncode)
        self.assertIn("incompatible", result.stderr)
        self.assertEqual(2, len(list(self.home.glob("*.avd"))))
        self.assert_ok(self.invoke("cleanup", "--apply", "--names", "RIPDPI_Pool_1"))
        self.assert_ok(self.run_child("pass", "--profile", "pixel_6"))

    def test_real_process_lease_contention_uses_only_free_second_slot(self):
        self.ready()
        lock = self.home / ".ripdpi-avd-pool/RIPDPI_Pool_1.lock"
        helper = subprocess.Popen([sys.executable, "-c",
            "import fcntl,sys,time; f=open(sys.argv[1],'r+'); "
            "fcntl.flock(f,fcntl.LOCK_EX); print('locked',flush=True); time.sleep(30)", str(lock)],
            stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual("locked\n", helper.stdout.readline())
            result = self.run_child("import os; print(os.environ['RIPDPI_AVD_NAME'])")
            self.assert_ok(result)
            self.assertIn("RIPDPI_Pool_2", result.stdout)
            cleanup = self.invoke("cleanup", "--apply", "--names", "RIPDPI_Pool_1")
            self.assertEqual(1, cleanup.returncode)
            self.assertIn("leased", cleanup.stdout)
        finally:
            helper.terminate()
            helper.wait(timeout=5)
            helper.stdout.close()

    def test_real_process_port_lease_prevents_reuse_without_open_socket(self):
        directory = Path("/tmp").resolve() / f"ripdpi-android-emulator-{os.getuid()}"
        directory.mkdir(mode=0o700, exist_ok=True)
        port, lease = POOL.free_port(directory)
        lease.close()
        lock = directory / f"port-{port}.lock"
        helper = subprocess.Popen([sys.executable, "-c",
            "import fcntl,sys,time; f=open(sys.argv[1],'r+'); "
            "fcntl.flock(f,fcntl.LOCK_EX); print('locked',flush=True); time.sleep(30)", str(lock)],
            stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual("locked\n", helper.stdout.readline())
            result = self.run_child("import os; print(os.environ['ANDROID_SERIAL'])")
            self.assert_ok(result)
            self.assertNotIn(f"emulator-{port}\n", result.stdout)
            emulator = next(cmd for cmd in self.commands() if cmd[0] == "emulator")
            self.assertNotEqual(str(port), emulator[emulator.index("-port") + 1])
        finally:
            helper.terminate()
            helper.wait(timeout=5)
            helper.stdout.close()
        self.assertTrue(lock.exists())

    def test_unexpected_emulator_exit_stops_workload_and_releases_port_lease(self):
        started = self.root / "workload-started"
        stopped = self.root / "workload-stopped"
        code = (
            "import signal,sys,time; from pathlib import Path; "
            f"signal.signal(signal.SIGTERM, lambda *_: (Path({str(stopped)!r}).write_text('yes'), sys.exit(0))); "
            f"Path({str(started)!r}).write_text('yes'); time.sleep(60)"
        )
        result = self.run_child(code, "--temporary", "--reason", "emulator death",
                                FAKE_EMULATOR_LIFETIME="2")
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertIn("exited while command was running", result.stderr)
        self.assertTrue(started.exists())
        self.assertTrue(stopped.exists())
        self.assertFalse(list(self.home.glob("*.avd")))
        self.assertFalse(list(self.state.glob("device-*")))
        emulator = next(cmd for cmd in self.commands() if cmd[0] == "emulator")
        port = emulator[emulator.index("-port") + 1]
        lock = Path("/tmp").resolve() / f"ripdpi-android-emulator-{os.getuid()}" / f"port-{port}.lock"
        with POOL.Lease(lock) as lease:
            self.assertTrue(lease.acquired)

    def test_second_wrapper_cannot_reuse_dead_emulator_serial_during_workload_shutdown(self):
        started = self.root / "first-started"
        stopping = self.root / "first-stopping"
        stopped = self.root / "first-stopped"
        code = (
            "import os,signal,sys,time; from pathlib import Path\n"
            "def stop(*_):\n"
            f"    Path({str(stopping)!r}).write_text('yes')\n"
            "    time.sleep(5)\n"
            f"    Path({str(stopped)!r}).write_text('yes')\n"
            "    sys.exit(0)\n"
            "signal.signal(signal.SIGTERM, stop)\n"
            f"Path({str(started)!r}).write_text(os.environ['ANDROID_SERIAL'])\n"
            "time.sleep(60)\n"
        )
        first = subprocess.Popen([sys.executable, str(SCRIPT), *self.base, "--",
                                  sys.executable, "-c", code], env=self.env,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 10
            while not started.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertTrue(started.exists())
            serial = started.read_text()
            port = serial.removeprefix("emulator-")
            os.kill(int((self.state / f"pid-{port}").read_text()), signal.SIGTERM)
            while not stopping.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertTrue(stopping.exists())
            self.assertFalse(stopped.exists())
            second = self.run_child("import os; print(os.environ['ANDROID_SERIAL'], os.environ['RIPDPI_AVD_NAME'])")
            self.assert_ok(second)
            self.assertIn("RIPDPI_Pool_2", second.stdout)
            self.assertNotIn(serial + " ", second.stdout)
            stdout, stderr = first.communicate(timeout=25)
            self.assertEqual(1, first.returncode, stdout + stderr)
            self.assertIn("exited while command was running", stderr)
            self.assertTrue(stopped.exists())
        finally:
            if first.poll() is None:
                first.terminate()
                first.communicate(timeout=25)

    def test_child_failure_keeps_pool_data_and_releases_lease(self):
        failure = self.run_child("raise SystemExit(7)")
        self.assertEqual(7, failure.returncode, failure.stderr)
        self.assertTrue((self.home / "RIPDPI_Pool_1.avd").exists())
        self.assert_ok(self.run_child())
        self.assertFalse(list(self.state.glob("device-*")))

    def test_normal_child_exit_stops_its_background_workers(self):
        started = self.root / "worker-started"
        stopped = self.root / "worker-stopped"
        grandchild = (
            "import signal,sys,time; from pathlib import Path; "
            f"signal.signal(signal.SIGTERM, lambda *_: (Path({str(stopped)!r}).write_text('yes'), sys.exit(0))); "
            f"Path({str(started)!r}).write_text('yes'); time.sleep(60)"
        )
        child = ("import subprocess,sys,time; from pathlib import Path; "
                 f"subprocess.Popen([sys.executable,'-c',{grandchild!r}]); "
                 f"p=Path({str(started)!r}); "
                 "\nwhile not p.exists(): time.sleep(.05)\n")
        self.assert_ok(self.run_child(child))
        self.assertTrue(stopped.exists())

    def test_cleanup_stops_emulator_after_child_cleanup_error_and_retains_metadata(self):
        args = POOL.parser().parse_args([*self.base, "--temporary", "--reason", "cleanup failure",
                                        "--", sys.executable, "-c", "pass"])
        args.command.pop(0)
        original = POOL.stop_process
        calls = []

        def fail_first(process):
            calls.append(process)
            original(process)
            if len(calls) == 1:
                raise POOL.PoolError("child shutdown not confirmed")

        with mock.patch.object(POOL, "stop_process", side_effect=fail_first):
            with self.assertRaisesRegex(POOL.PoolError, "Cleanup incomplete"):
                POOL.run(POOL.Pool(self.env), args)
        self.assertEqual(2, len(calls))
        self.assertFalse(list(self.state.glob("device-*")))
        self.assertEqual(1, len(list((self.home / ".ripdpi-avd-pool").glob("*.json"))))
        self.assert_ok(self.invoke("cleanup", "--apply"))

    def test_temporary_success_and_failure_remove_avd_but_keep_lock_identity(self):
        for code, status in [("pass", 0), ("raise SystemExit(8)", 8)]:
            result = self.run_child(code, "--temporary", "--reason", "fresh storage test")
            self.assertEqual(status, result.returncode, result.stderr)
            self.assertFalse(list(self.home.glob("*.avd")))
            self.assertFalse(list(self.home.glob("*.ini")))
            self.assertFalse(list((self.home / ".ripdpi-avd-pool").glob("*.json")))
        self.assertEqual(2, len(list((self.home / ".ripdpi-avd-pool").glob("RIPDPI_Temp_*.lock"))))

    def test_signal_stops_child_and_emulator_before_temporary_deletion(self):
        started = self.root / "child-started"
        child_code = f"from pathlib import Path; import time; Path({str(started)!r}).write_text('yes'); time.sleep(60)"
        supervisor = subprocess.Popen([sys.executable, str(SCRIPT), *self.base, "--temporary",
            "--reason", "interrupt test", "--", sys.executable, "-c", child_code],
            env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 10
            while not started.exists() and time.monotonic() < deadline:
                time.sleep(.05)
            self.assertTrue(started.exists())
            supervisor.send_signal(signal.SIGTERM)
            stdout, stderr = supervisor.communicate(timeout=25)
            self.assertEqual(143, supervisor.returncode, stdout + stderr)
            self.assertFalse(list(self.state.glob("device-*")))
            self.assertFalse(list(self.home.glob("*.avd")))
        finally:
            if supervisor.poll() is None:
                supervisor.kill()
                supervisor.communicate()

    def test_boot_failure_does_not_run_child_and_cleans_temporary(self):
        result = self.run_child("print('must not run')", "--temporary", "--reason", "boot test",
                                "--boot-timeout", "1", FAKE_NO_BOOT="1")
        self.assertEqual(1, result.returncode)
        self.assertNotIn("must not run", result.stdout)
        self.assertIn("Boot timeout", result.stderr)
        self.assertFalse(list(self.home.glob("*.avd")))

    def test_transient_boot_probe_timeout_retries_until_ready(self):
        result = self.run_child("print('boot ready')", "--boot-timeout", "12", FAKE_FIRST_BOOT_TIMEOUT="1")
        self.assert_ok(result)
        self.assertIn("boot ready", result.stdout)
        probes = [call for call in self.commands() if call[0] == "adb" and call[-1] == "sys.boot_completed"]
        self.assertGreaterEqual(len(probes), 2)

    def test_probe_timeout_cannot_extend_overall_boot_deadline(self):
        started = time.monotonic()
        result = self.run_child("print('must not run')", "--temporary", "--reason", "deadline test",
                                "--boot-timeout", "1", FAKE_FIRST_BOOT_TIMEOUT="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("Boot timeout", result.stderr)
        self.assertNotIn("must not run", result.stdout)
        self.assertLess(time.monotonic() - started, 8)
        self.assertFalse(list(self.home.glob("*.avd")))

    def test_failed_temporary_creation_is_removed(self):
        result = self.run_child("pass", "--temporary", "--reason", "create failure", FAKE_CREATE_EXIT="2")
        self.assertEqual(1, result.returncode)
        self.assertIn("avdmanager failed", result.stderr)
        self.assertFalse(list(self.home.glob("*.avd")))

    def test_requires_installed_image_and_explicit_temporary_reason(self):
        missing = self.invoke("run", "--api", "36", "--arch", "arm64-v8a", "--", "true")
        self.assertEqual(1, missing.returncode)
        self.assertIn("not installed", missing.stderr)
        self.assertFalse([cmd for cmd in self.commands() if cmd[0] == "avdmanager"])
        reason = self.run_child("pass", "--temporary")
        self.assertEqual(2, reason.returncode)
        detached = self.invoke(*self.base)
        self.assertEqual(2, detached.returncode)

    def test_cleanup_is_dry_run_and_requires_named_legacy_opt_in(self):
        self.ready()
        self.assert_ok(self.invoke("cleanup"))
        self.assertTrue((self.home / "RIPDPI_Pool_1.avd").exists())
        name = "RIPDPI_Docs_20261013"
        directory = self.home / f"{name}.avd"
        directory.mkdir()
        (directory / "data").write_text("keep until approved")
        (self.home / f"{name}.ini").write_text(f"path={directory}\n")
        refused = self.invoke("cleanup", "--apply", "--names", name)
        self.assertEqual(1, refused.returncode)
        self.assertTrue(directory.exists())
        self.assertEqual(2, self.invoke("cleanup", "--legacy").returncode)
        self.assert_ok(self.invoke("cleanup", "--apply", "--legacy", "--names", name))
        self.assertFalse(directory.exists())

    def test_cleanup_skips_active_process_and_adb_identity(self):
        self.ready()
        name = "RIPDPI_Pool_1"
        for process in [f"/sdk/emulator @{name}", f"/sdk/qemu -avd {name}"]:
            result = self.invoke("cleanup", "--apply", "--names", name, FAKE_PS=process)
            self.assertEqual(1, result.returncode)
            self.assertIn("emulator process", result.stdout)
        marker = self.state / "device-5554"
        marker.write_text(name)
        result = self.invoke("cleanup", "--apply", "--names", name)
        self.assertEqual(1, result.returncode)
        self.assertIn("adb device emulator-5554", result.stdout)
        self.assertTrue((self.home / f"{name}.avd").exists())

    def test_cleanup_fails_closed_on_offline_emulator_and_held_emulator_files(self):
        self.ready()
        name = "RIPDPI_Pool_1"
        result = self.invoke("cleanup", "--apply", "--names", name, FAKE_OFFLINE="1")
        self.assertEqual(1, result.returncode)
        self.assertIn("offline emulator", result.stdout)
        result = self.invoke("cleanup", "--apply", "--names", name, FAKE_LSOF="0")
        self.assertEqual(1, result.returncode)
        self.assertIn("open emulator files", result.stdout)

    def test_foreign_symlink_and_external_pointer_are_preserved(self):
        self.ready()
        directory = self.home / "RIPDPI_Pool_1.avd"
        (directory / "foreign").symlink_to(self.state, target_is_directory=True)
        result = self.invoke("cleanup", "--apply", "--names", "RIPDPI_Pool_1")
        self.assertEqual(1, result.returncode)
        self.assertIn("symlink", result.stdout)
        (directory / "foreign").unlink()
        (self.home / "RIPDPI_Pool_1.ini").write_text(f"path={self.state}\n")
        result = self.invoke("cleanup", "--apply", "--names", "RIPDPI_Pool_1")
        self.assertEqual(1, result.returncode)
        self.assertIn("outside", result.stdout)
        foreign = self.invoke("cleanup", "--apply", "--legacy", "--names", "Personal_20261013")
        self.assertEqual(1, foreign.returncode)
        self.assertIn("Not an approved", foreign.stdout)
        self.assertTrue(directory.exists())

    def test_avd_home_precedence_and_legacy_sdk_home(self):
        self.assertEqual(self.home, POOL.avd_home({"ANDROID_AVD_HOME": str(self.home),
            "ANDROID_USER_HOME": "/ignored", "ANDROID_SDK_HOME": "/also-ignored"}))
        self.assertEqual(Path("/android-user/avd"), POOL.avd_home({"ANDROID_USER_HOME": "/android-user"}))
        self.assertEqual(Path("/legacy/.android/avd"), POOL.avd_home({"ANDROID_SDK_HOME": "/legacy"}))

    def test_metadata_tampering_and_config_mismatch_never_reset_data(self):
        self.ready()
        config = self.home / "RIPDPI_Pool_1.avd/config.ini"
        config.write_text(config.read_text().replace("arm64-v8a", "x86_64"))
        result = self.invoke(*self.base, "--pool-size", "1", "--", "true")
        self.assertEqual(1, result.returncode)
        self.assertIn("incompatible", result.stderr)
        self.assertIn("x86_64", config.read_text())
        metadata = self.home / ".ripdpi-avd-pool/RIPDPI_Pool_1.json"
        metadata.write_text('{"schema": 999}')
        result = self.invoke("cleanup", "--apply", "--names", "RIPDPI_Pool_1")
        self.assertEqual(1, result.returncode)
        self.assertIn("Invalid pool metadata", result.stdout)

    def test_actual_image_path_mismatch_prevents_reuse(self):
        self.ready()
        config = self.home / "RIPDPI_Pool_1.avd/config.ini"
        config.write_text(config.read_text().replace("android-37.0", "android-36"))
        result = self.invoke(*self.base, "--pool-size", "1", "--", "true")
        self.assertEqual(1, result.returncode)
        self.assertIn("incompatible", result.stderr)
        self.assertIn("android-36", config.read_text())


class ProcessGroupCleanupTest(unittest.TestCase):
    def test_darwin_unknown_execution_states_are_conservatively_live(self):
        for state in ("?", "?E", "?Es"):
            with self.subTest(state=state):
                status = subprocess.CompletedProcess([], 0, f"12345 {state}\n", "")
                with mock.patch.object(POOL, "command", return_value=status):
                    self.assertTrue(POOL.group_has_live_members(12345))

    def test_darwin_exit_state_is_polled_until_the_group_disappears(self):
        process = mock.Mock(pid=12345)
        exiting = subprocess.CompletedProcess([], 0, "12345 ?Es\n", "")
        gone = subprocess.CompletedProcess([], 0, "", "")
        with mock.patch.object(POOL.os, "killpg"), \
                mock.patch.object(POOL, "command", side_effect=[exiting, gone, gone]) as inspect, \
                mock.patch.object(POOL.time, "sleep"):
            POOL.stop_process(process)
        self.assertEqual(3, inspect.call_count)
        process.wait.assert_called_once_with(timeout=5)

    def test_exited_zombie_leader_is_reaped_during_cleanup(self):
        process = subprocess.Popen([sys.executable, "-c", "pass"], start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                state = subprocess.run(["/bin/ps", "-o", "stat=", "-p", str(process.pid)],
                                       text=True, capture_output=True, check=True).stdout.strip()
                if state.startswith("Z"):
                    break
                time.sleep(.02)
            else:
                self.fail("Child did not reach zombie state")
            POOL.stop_process(process)
            self.assertEqual(0, process.returncode)
        finally:
            process.wait(timeout=5)

    def test_permission_error_with_live_member_remains_an_error(self):
        process = mock.Mock(pid=12345)
        process.poll.return_value = 0
        status = subprocess.CompletedProcess([], 0, "12345 S\n", "")
        with mock.patch.object(POOL.os, "killpg", side_effect=PermissionError(1, "denied")), \
                mock.patch.object(POOL, "command", return_value=status) as inspect:
            with self.assertRaises(PermissionError):
                POOL.stop_process(process)
            self.assertEqual("/bin/ps", inspect.call_args.args[0][0])

    def test_permission_error_with_only_zombies_confirms_stopped_group(self):
        process = mock.Mock(pid=12345)
        process.poll.return_value = 0
        status = subprocess.CompletedProcess([], 0, "12345 Z\n54321 S\n", "")
        with mock.patch.object(POOL.os, "killpg", side_effect=PermissionError(1, "denied")), \
                mock.patch.object(POOL, "command", return_value=status):
            POOL.stop_process(process)
        process.wait.assert_called_once_with(timeout=5)

    def test_failed_or_malformed_group_inspection_blocks_cleanup(self):
        for status in (subprocess.CompletedProcess([], 1, "", "failed"),
                       subprocess.CompletedProcess([], 0, "ambiguous\n", ""),
                       subprocess.CompletedProcess([], 0, "12345 !\n", ""),
                       subprocess.CompletedProcess([], 0, "12345 S?\n", "")):
            with self.subTest(status=status):
                process = mock.Mock(pid=12345)
                process.poll.return_value = 0
                with mock.patch.object(POOL.os, "killpg", side_effect=PermissionError(1, "denied")), \
                        mock.patch.object(POOL, "command", return_value=status):
                    with self.assertRaises(POOL.PoolError):
                        POOL.stop_process(process)

    def test_successful_zero_signal_with_only_zombies_is_not_live(self):
        process = mock.Mock(pid=12345)
        process.poll.return_value = 0
        status = subprocess.CompletedProcess([], 0, "12345 Z\n", "")
        with mock.patch.object(POOL.os, "killpg"), \
                mock.patch.object(POOL, "command", return_value=status):
            POOL.stop_process(process)
        process.wait.assert_called_once_with(timeout=5)

    def test_live_group_after_kill_is_not_reported_as_stopped(self):
        for state in ("S", "?Es"):
            with self.subTest(state=state):
                process = mock.Mock(pid=12345)
                status = subprocess.CompletedProcess([], 0, f"12345 {state}\n", "")
                with mock.patch.object(POOL.os, "killpg"), \
                        mock.patch.object(POOL, "command", return_value=status), \
                        mock.patch.object(POOL.time, "monotonic", side_effect=[0, 16, 16, 22]):
                    with self.assertRaisesRegex(POOL.PoolError, "still active"):
                        POOL.stop_process(process)


if __name__ == "__main__":
    unittest.main()
