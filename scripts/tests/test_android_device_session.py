#!/usr/bin/env python3

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "test-lab/scripts/android-device-session.py"


class AndroidDeviceSessionTest(unittest.TestCase):
    def make_adb(self, directory: Path) -> tuple[Path, Path]:
        log = directory / "adb.log"
        adb = directory / "adb"
        adb.write_text(
            """#!/usr/bin/env python3
import os
import pathlib
import sys

args = sys.argv[1:]
pathlib.Path(os.environ['ADB_LOG']).open('a').write(' '.join(args) + '\\n')
command = args[2:]
if command == ['get-serialno']:
    print(args[1])
elif command == ['shell', 'am', 'get-current-user']:
    print('10')
elif command[:3] == ['shell', 'settings', 'get']:
    key = command[-1]
    print({'always_on_vpn_app': 'com.example.vpn', 'always_on_vpn_lockdown': '1'}.get(key, 'null'))
elif command[:3] == ['shell', 'pm', 'path']:
    if command[-1] == 'com.poyka.ripdpi':
        print('package:/data/app/base.apk')
elif command[:1] == ['pull']:
    pathlib.Path(command[-1]).write_bytes(b'original-apk')
elif command[:2] == ['exec-out', 'run-as']:
    sys.stdout.buffer.write(b'original-data')
elif command[:3] == ['shell', 'dumpsys', 'package']:
    print('''install permissions:
      android.permission.INTERNET: granted=true
    User 0:
      runtime permissions:
        android.permission.CAMERA: granted=true, flags=[ USER_SET ]
    User 10:
      runtime permissions:
        android.permission.ACCESS_FINE_LOCATION: granted=true, flags=[ USER_SET ]
        android.permission.CAMERA: granted=false, flags=[ USER_FIXED|USER_SET ]''')
elif command[:5] == ['shell', 'cmd', 'appops', 'get', '--user']:
    print('FINE_LOCATION: foreground; time=+1h\\nCAMERA: ignore')
elif command[:1] == ['install-multiple']:
    for candidate in command[4:]:
        if not pathlib.Path(candidate).is_file():
            raise SystemExit(4)
elif command[:3] == ['shell', 'run-as', 'com.poyka.ripdpi']:
    if sys.stdin.buffer.read() != b'original-data':
        raise SystemExit(5)
raise SystemExit(0)
""",
            encoding="utf-8",
        )
        adb.chmod(0o755)
        return adb, log

    def invoke(self, adb: Path, log: Path, *args: str) -> subprocess.CompletedProcess[str]:
        import os

        env = os.environ.copy()
        env["ADB_LOG"] = str(log)
        return subprocess.run(
            [sys.executable, str(SCRIPT), *args, "--adb", str(adb)],
            text=True,
            capture_output=True,
            env=env,
            check=False,
        )

    def test_capture_and_restore_exact_device_state(self) -> None:
        with tempfile.TemporaryDirectory() as directory_raw:
            directory = Path(directory_raw)
            adb, log = self.make_adb(directory)
            state = directory / "state"
            capture = self.invoke(
                adb,
                log,
                "capture",
                "--serial",
                "pixel-8",
                "--state-dir",
                str(state),
                "--package",
                "com.poyka.ripdpi",
                "--package",
                "com.poyka.ripdpi.test",
            )
            self.assertEqual(0, capture.returncode, capture.stderr)
            manifest = json.loads((state / "manifest.json").read_text())
            self.assertEqual("pixel-8", manifest["serial"])
            self.assertEqual("10", manifest["userId"])
            self.assertTrue(manifest["packages"][0]["installed"])
            self.assertFalse(manifest["packages"][1]["installed"])

            restore = self.invoke(
                adb,
                log,
                "restore",
                "--serial",
                "pixel-8",
                "--state-dir",
                str(state),
            )
            self.assertEqual(0, restore.returncode, restore.stderr)
            calls = log.read_text()
            self.assertIn("-s pixel-8 install-multiple -r -d -t", calls)
            self.assertIn("settings put secure always_on_vpn_app com.example.vpn", calls)
            self.assertIn("settings delete global stay_on_while_plugged_in", calls)
            self.assertIn("pm grant --user 10 com.poyka.ripdpi android.permission.ACCESS_FINE_LOCATION", calls)
            self.assertIn("pm revoke --user 10 com.poyka.ripdpi android.permission.CAMERA", calls)
            self.assertNotIn("pm grant --user 10 com.poyka.ripdpi android.permission.INTERNET", calls)
            self.assertIn("cmd appops reset --user 10 com.poyka.ripdpi", calls)
            self.assertIn("cmd appops set --user 10 com.poyka.ripdpi CAMERA ignore", calls)

    def test_rejects_restore_on_different_serial(self) -> None:
        with tempfile.TemporaryDirectory() as directory_raw:
            directory = Path(directory_raw)
            adb, log = self.make_adb(directory)
            state = directory / "state"
            state.mkdir()
            (state / "manifest.json").write_text(
                json.dumps({"version": "ripdpi_android_device_session_v2", "serial": "pixel-7"})
            )
            result = self.invoke(
                adb,
                log,
                "restore",
                "--serial",
                "pixel-8",
                "--state-dir",
                str(state),
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("does not match", result.stderr)

    def test_capture_fails_closed_when_existing_app_data_cannot_be_backed_up(self) -> None:
        with tempfile.TemporaryDirectory() as directory_raw:
            directory = Path(directory_raw)
            adb, log = self.make_adb(directory)
            adb.write_text(adb.read_text().replace("sys.stdout.buffer.write(b'original-data')", "raise SystemExit(9)"))
            state = directory / "state"
            result = self.invoke(
                adb,
                log,
                "capture",
                "--serial",
                "pixel-8",
                "--state-dir",
                str(state),
                "--package",
                "com.poyka.ripdpi",
            )
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(state.exists())

    def test_destructive_runners_require_serial_and_restore_session(self) -> None:
        transactional = {
            ROOT / "test-lab/scripts/run-vpn-e2e.sh": '"$script_dir/adb-install-debug.sh"',
            ROOT / "test-lab/scripts/run-proxy-e2e.sh": '"$script_dir/adb-install-debug.sh"',
            ROOT / "scripts/ci/run-android-so-bind-physical-e2e.sh": 'install_and_verify_apk "$app_apk"',
            ROOT / "test-lab/scripts/run-rooted-emulator-netem.sh": '"$lab_root/scripts/adb-install-debug.sh"',
            ROOT / "test-lab/scripts/run-mock-relay-matrix.sh": '"$lab_root/scripts/adb-install-debug.sh"',
        }
        for path, mutation_marker in transactional.items():
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                self.assertIn("android-device-session.py\" capture", source)
                self.assertIn("android-device-session.py\" restore", source)
                self.assertLess(
                    source.index("android-device-session.py\" capture"),
                    source.index(mutation_marker),
                )
                self.assertIn("recovery backup retained", source.lower())

        for path in (
            ROOT / "test-lab/scripts/run-host-fault-matrix.sh",
            ROOT / "test-lab/scripts/run-netem-forwarder.sh",
        ):
            with self.subTest(path=path.name):
                source = path.read_text(encoding="utf-8")
                self.assertIn("never auto-selects a target", source)
                self.assertNotIn("devices -l | awk", source)


if __name__ == "__main__":
    unittest.main()
