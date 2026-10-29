"""Process-lifecycle regression for the standalone AWG interop runner."""

import importlib.util
import json
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

    def test_peer_source_rejects_revision_checksum_and_version_substitution(self):
        version = "v3.1.20260814"
        good = {"Version": version, "Origin": {"Hash": "1b86b2ae0e493e7ea93f8c1a0f0cb6735b1551f1"},
                "Sum": "h1:l2AhBD+sFycU8Im81n/bZORMxW7fWtlZJEuJ4Hh0+z0=", "Dir": "/fixture/source"}
        self.assertEqual(RUNNER.verify_peer_source(version, good), Path("/fixture/source"))
        for key, wrong in (("Version", "v3.1.20260828"), ("Origin", {"Hash": "0" * 40}), ("Sum", "wrong")):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "identity mismatch"):
                RUNNER.verify_peer_source(version, dict(good, **{key: wrong}))

    def test_download_diagnostics_do_not_corrupt_json_stdout(self):
        with tempfile.TemporaryDirectory(prefix="awg-download-test-") as directory:
            root = Path(directory)
            with (root / "metadata.json").open("w") as output, (root / "download.log").open("w") as errors:
                RUNNER.checked([sys.executable, "-c", "import sys; print('{}'); print('go: downloading toolchain', file=sys.stderr)"],
                               root, dict(os.environ), output=output, stderr=errors)
            self.assertEqual(json.loads((root / "metadata.json").read_text()), {})
            self.assertIn("go: downloading", (root / "download.log").read_text())

    def test_large_peer_observation_is_drained_before_waiting_for_exit(self):
        with tempfile.TemporaryDirectory(prefix="awg-report-pipe-test-") as directory:
            case = Path(directory)
            peer, runtime = case / "peer", case / "runtime"
            peer.write_text(f"#!{sys.executable}\nimport json,sys\nprint(json.dumps({{'endpoint':'127.0.0.1:51820'}}), flush=True)\nsys.stdin.readline()\nprint(json.dumps({{'wire_observation':{{'payload':'x'*200000}}}}), flush=True)\n")
            runtime.write_text(f"#!{sys.executable}\n")
            peer.chmod(0o700)
            runtime.chmod(0o700)
            result = RUNNER.run_case(peer, runtime, case, dict(os.environ), 1)
            self.assertEqual(len(result["payload"]), 200000)

    def test_missing_peer_endpoint_is_a_controlled_failure(self):
        with tempfile.TemporaryDirectory(prefix="awg-startup-test-") as directory:
            case = Path(directory)
            peer = case / "peer"
            peer.write_text(f"#!{sys.executable}\nprint('{{}}', flush=True)\n")
            peer.chmod(0o700)
            with self.assertRaisesRegex(ValueError, "no endpoint"):
                RUNNER.run_case(peer, case / "unused-test", case, dict(os.environ), 1)

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
