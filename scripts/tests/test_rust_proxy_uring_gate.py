"""Check the Linux io_uring gate command contract, without compiling Rust."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
GATE = ROOT / "scripts/ci/run-rust-proxy-uring-tests.sh"


class RustProxyUringGateTests(unittest.TestCase):
    def run_gate(self, root: Path, platform: str, result: int = 0):
        scripts = root / "scripts/ci"
        scripts.mkdir(parents=True)
        shutil.copyfile(GATE, scripts / GATE.name)
        # This adapter records command construction only. It does not claim
        # real io_uring execution, which belongs to the hosted Linux test.
        capture = root / "command.json"
        (scripts / "cargo-guarded.sh").write_text(
            'exec "$GATE_TEST_PYTHON" - "$@" <<\'PY\'\n'
            "import json, os, pathlib, sys\n"
            "pathlib.Path(os.environ['GATE_TEST_CAPTURE']).write_text(json.dumps({\n"
            "    'args': sys.argv[1:], 'required': os.environ.get('RIPDPI_REQUIRE_IO_URING')}))\n"
            "sys.exit(int(os.environ['GATE_TEST_RESULT']))\nPY\n"
        )
        binaries = root / "bin"
        binaries.mkdir()
        uname = binaries / "uname"
        uname.write_text('#!/bin/sh\nprintf "%s\\n" "$GATE_TEST_PLATFORM"\n')
        uname.chmod(0o755)
        environment = {
            **os.environ,
            "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
            "GATE_TEST_PLATFORM": platform,
            "GATE_TEST_RESULT": str(result),
            "GATE_TEST_CAPTURE": str(capture),
            "GATE_TEST_PYTHON": sys.executable,
            "RIPDPI_REQUIRE_IO_URING": "0",
        }
        process = subprocess.run(
            ["bash", str(scripts / GATE.name)],
            cwd=root,
            env=environment,
            capture_output=True,
            text=True,
            timeout=5,
        )
        return process, json.loads(capture.read_text()) if capture.exists() else None

    def test_linux_runs_full_feature_suite_with_required_ring_and_no_retries(self):
        with tempfile.TemporaryDirectory(prefix="uring gate ") as directory:
            root = Path(directory)
            process, capture = self.run_gate(root, "Linux")
            self.assertEqual(0, process.returncode, process.stderr)
            self.assertEqual("1", capture["required"])
            self.assertEqual(
                [
                    "cargo",
                    "nextest",
                    "run",
                    "--locked",
                    "--manifest-path",
                    str(root / "native/rust/Cargo.toml"),
                    "-p",
                    "ripdpi-proxy-runtime",
                    "--lib",
                    "--features",
                    "io-uring",
                    "--profile",
                    "ci",
                    "--build-jobs",
                    "4",
                    "--test-threads",
                    "4",
                    "--retries",
                    "0",
                    "--no-tests",
                    "fail",
                ],
                capture["args"],
            )

    def test_non_linux_fails_before_cargo(self):
        for platform in ("Darwin", "FreeBSD"):
            with (
                self.subTest(platform=platform),
                tempfile.TemporaryDirectory() as directory,
            ):
                process, capture = self.run_gate(Path(directory), platform)
                self.assertEqual(2, process.returncode)
                self.assertIn("requires Linux and real io_uring", process.stderr)
                self.assertIsNone(capture)

    def test_cargo_failure_is_returned_without_retry_or_fallback(self):
        with tempfile.TemporaryDirectory() as directory:
            process, _ = self.run_gate(Path(directory), "Linux", 42)
            self.assertEqual(42, process.returncode)

    def test_workflow_reuses_existing_linux_job_and_bounds_new_step(self):
        source = (ROOT / ".github/workflows/ci.yml").read_text()
        job = re.search(
            r"(?ms)^  rust-workspace-tests:\n.*?(?=^  [\w-]+:\n)", source
        ).group()
        self.assertIn("runs-on: ubuntu-latest", job)
        self.assertIn("shared-key: rust-host-test", job)
        step = re.search(
            r"(?ms)^      - name: Run Linux io_uring proxy runtime tests\n.*?(?=^      - |^      #)",
            job,
        ).group()
        self.assertIn("if: ${{ !cancelled() }}", step)
        self.assertIn("timeout-minutes: 10", step)
        self.assertIn("run: bash scripts/ci/run-rust-proxy-uring-tests.sh", step)
        self.assertNotIn("continue-on-error", step)
        self.assertLess(job.index("run-rust-workspace-tests.sh"), job.index(GATE.name))
        self.assertLess(job.index(GATE.name), job.index("run-rust-network-e2e.sh"))
        self.assertEqual(
            1, source.count("run: bash scripts/ci/run-rust-proxy-uring-tests.sh")
        )


if __name__ == "__main__":
    unittest.main()
