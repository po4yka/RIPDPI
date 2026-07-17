#!/usr/bin/env python3
"""Process-level contract tests for the local network fixture launcher."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import textwrap
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
STARTER = ROOT / "scripts/ci/start-local-network-fixture.sh"
STOPPER = ROOT / "scripts/ci/stop-local-network-fixture.sh"


def write_executable(path: Path, content: str) -> None:
    path.write_text(textwrap.dedent(content), encoding="utf-8")
    path.chmod(0o755)


class StartLocalNetworkFixtureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        self.ci_dir = self.repo / "scripts/ci"
        self.ci_dir.mkdir(parents=True)
        shutil.copyfile(STARTER, self.ci_dir / STARTER.name)
        self.bin_dir = self.repo / "bin"
        self.bin_dir.mkdir()
        self.runner_temp = self.repo / "runner-temp"
        self.runner_temp.mkdir()
        self.pid_file = self.runner_temp / "fixture.pid"

        write_executable(
            self.bin_dir / "cargo",
            """\
            #!/usr/bin/env python3
            import time

            while True:
                time.sleep(1)
            """,
        )
        write_executable(
            self.bin_dir / "curl",
            """\
            #!/usr/bin/env python3
            import os
            import sys
            from pathlib import Path

            url = sys.argv[-1]
            counter = Path(os.environ["FAKE_CURL_COUNTER"])
            if url.endswith("/health"):
                attempts = (
                    int(counter.read_text(encoding="utf-8")) + 1
                    if counter.exists()
                    else 1
                )
                counter.write_text(str(attempts), encoding="utf-8")
                raise SystemExit(0 if attempts >= 65 else 22)
            if url.endswith("/manifest"):
                print('{"status":"ready"}')
                raise SystemExit(0)
            raise SystemExit(23)
            """,
        )
        write_executable(self.bin_dir / "sleep", "#!/usr/bin/env bash\nexit 0\n")

    def tearDown(self) -> None:
        subprocess.run(
            ["bash", str(STOPPER), str(self.pid_file)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.temp.cleanup()

    def test_launcher_allows_cold_build_longer_than_sixty_polls(self) -> None:
        counter = self.runner_temp / "curl-count"
        env = os.environ.copy()
        env.update(
            {
                "FAKE_CURL_COUNTER": str(counter),
                "PATH": f"{self.bin_dir}:{env['PATH']}",
            }
        )

        completed = subprocess.run(
            [
                "bash",
                "scripts/ci/start-local-network-fixture.sh",
                str(self.runner_temp / "fixture.log"),
                str(self.runner_temp / "fixture-manifest.json"),
                str(self.pid_file),
            ],
            cwd=self.repo,
            capture_output=True,
            check=False,
            env=env,
            text=True,
            timeout=5,
        )

        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertGreaterEqual(int(counter.read_text(encoding="utf-8")), 65)
        self.assertEqual(
            (self.runner_temp / "fixture-manifest.json").read_text(encoding="utf-8").strip(),
            '{"status":"ready"}',
        )

    def test_launcher_rejects_invalid_startup_timeout(self) -> None:
        env = os.environ.copy()
        env["RIPDPI_FIXTURE_STARTUP_TIMEOUT_SECONDS"] = "0"

        completed = subprocess.run(
            [
                "bash",
                "scripts/ci/start-local-network-fixture.sh",
                str(self.runner_temp / "fixture.log"),
                str(self.runner_temp / "fixture-manifest.json"),
                str(self.pid_file),
            ],
            cwd=self.repo,
            capture_output=True,
            check=False,
            env=env,
            text=True,
            timeout=5,
        )

        self.assertEqual(completed.returncode, 2)
        self.assertIn("must be a positive integer", completed.stderr)
        self.assertFalse(self.pid_file.exists())


if __name__ == "__main__":
    unittest.main()
