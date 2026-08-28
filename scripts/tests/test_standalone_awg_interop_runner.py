"""Process-lifecycle regression for the standalone AWG interop runner."""

import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location(
    "awg_interop_runner", Path(__file__).with_name("run-standalone-awg-interop.py")
)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class ProcessCleanupTests(unittest.TestCase):
    def test_peer_go_toolchain_tracks_go_mod_directive(self):
        with tempfile.TemporaryDirectory(prefix="awg-go-toolchain-test-") as directory:
            go_mod = Path(directory) / "go.mod"
            go_mod.write_text("module ripdpi.test/peer\n\ngo 1.25.0\n", encoding="utf-8")

            self.assertEqual(RUNNER.peer_go_toolchain(go_mod), "go1.25.0")

    def test_peer_go_toolchain_rejects_missing_go_directive(self):
        with tempfile.TemporaryDirectory(prefix="awg-go-toolchain-test-") as directory:
            go_mod = Path(directory) / "go.mod"
            go_mod.write_text("module ripdpi.test/peer\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "missing supported Go directive"):
                RUNNER.peer_go_toolchain(go_mod)

    def test_timeout_terminates_descendant_that_ignores_term(self):
        with tempfile.TemporaryDirectory(prefix="awg-timeout-test-") as directory:
            pid_file = Path(directory) / "child.pid"
            child = "import signal; signal.signal(signal.SIGTERM, signal.SIG_IGN); signal.pause()"
            parent = (
                "import subprocess,sys,signal; from pathlib import Path; "
                "signal.signal(signal.SIGTERM,signal.SIG_IGN); "
                "child=subprocess.Popen([sys.executable,'-c',sys.argv[2],'awg-timeout-owned-child']); "
                "Path(sys.argv[1]).write_text(str(child.pid)); signal.pause()"
            )
            try:
                with self.assertRaises(subprocess.TimeoutExpired):
                    RUNNER.checked([sys.executable, "-c", parent, str(pid_file), child],
                                   Path(directory), dict(os.environ), timeout=1)
                self.assertTrue(pid_file.exists(), "child must start before timeout")
                pid = int(pid_file.read_text())
                state = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)],
                                       capture_output=True, text=True, check=False).stdout.strip()
                self.assertTrue(not state or state.startswith("Z"), f"descendant still running: {state}")
            finally:
                if pid_file.exists():
                    pid = int(pid_file.read_text())
                    command = subprocess.run(["ps", "-o", "command=", "-p", str(pid)],
                                             capture_output=True, text=True, check=False).stdout
                    if "awg-timeout-owned-child" in command:
                        try:
                            os.kill(pid, signal.SIGKILL)
                        except ProcessLookupError:
                            pass


if __name__ == "__main__":
    unittest.main()
