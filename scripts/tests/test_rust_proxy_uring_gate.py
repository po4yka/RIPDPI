"""Check the io_uring gate platform refusal and child exit status."""

from __future__ import annotations

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

GATE = Path(__file__).resolve().parents[1] / "ci/run-rust-proxy-uring-tests.sh"


class RustProxyUringGateTests(unittest.TestCase):
    def run_gate(self, root: Path, platform: str, result: int):
        scripts = root / "scripts/ci"
        scripts.mkdir(parents=True)
        shutil.copyfile(GATE, scripts / GATE.name)
        (scripts / "cargo-guarded.sh").write_text('exit "$GATE_TEST_RESULT"\n')
        binaries = root / "bin"
        binaries.mkdir()
        uname = binaries / "uname"
        uname.write_text('#!/bin/sh\nprintf "%s\\n" "$GATE_TEST_PLATFORM"\n')
        uname.chmod(0o755)
        return subprocess.run(
            ["bash", str(scripts / GATE.name)],
            cwd=root,
            env={
                **os.environ,
                "PATH": str(binaries) + os.pathsep + os.environ["PATH"],
                "GATE_TEST_PLATFORM": platform,
                "GATE_TEST_RESULT": str(result),
            },
            capture_output=True,
            text=True,
            timeout=5,
        )

    def test_non_linux_is_rejected(self):
        for platform in ("Darwin", "FreeBSD"):
            with (
                self.subTest(platform=platform),
                tempfile.TemporaryDirectory() as directory,
            ):
                process = self.run_gate(Path(directory), platform, 42)
                self.assertEqual(2, process.returncode)
                self.assertIn("requires Linux and real io_uring", process.stderr)

    def test_child_failure_reaches_the_caller(self):
        for result in (1, 42):
            with (
                self.subTest(result=result),
                tempfile.TemporaryDirectory(prefix="uring gate ") as directory,
            ):
                process = self.run_gate(Path(directory), "Linux", result)
                self.assertEqual(result, process.returncode)


if __name__ == "__main__":
    unittest.main()
