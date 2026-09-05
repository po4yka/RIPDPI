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
            import os
            import time

            if os.environ.get("FAKE_CARGO_PID"):
                open(os.environ["FAKE_CARGO_PID"], "w", encoding="utf-8").write(str(os.getpid()))

            while True:
                time.sleep(1)
            """,
        )
        write_executable(
            self.bin_dir / "curl",
            """\
            #!/usr/bin/env bash
            url="${!#}"
            case "$url" in
                */health)
                    attempts=0
                    if [[ -f "$FAKE_CURL_COUNTER" ]]; then
                        read -r attempts < "$FAKE_CURL_COUNTER"
                    fi
                    attempts=$((attempts + 1))
                    echo "$attempts" > "$FAKE_CURL_COUNTER"
                    (( attempts >= 65 )) && exit 0
                    exit 22
                    ;;
                */manifest)
                    echo '{"status":"ready"}'
                    exit 0
                    ;;
            esac
            exit 23
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

    def test_timeout_stops_only_the_owned_fixture_and_removes_identity(self) -> None:
        counter = self.runner_temp / "curl-count"
        observed_pid = self.runner_temp / "cargo.pid"
        env = os.environ.copy()
        env.update(
            {
                "FAKE_CURL_COUNTER": str(counter),
                "FAKE_CARGO_PID": str(observed_pid),
                "PATH": f"{self.bin_dir}:{env['PATH']}",
                "RIPDPI_FIXTURE_STARTUP_TIMEOUT_SECONDS": "2",
            }
        )
        (self.bin_dir / "curl").write_text(
            "#!/usr/bin/env bash\nexit 22\n", encoding="utf-8"
        )
        (self.bin_dir / "curl").chmod(0o755)

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

        self.assertNotEqual(0, completed.returncode)
        self.assertIn("Timed out", completed.stderr)
        self.assertTrue(observed_pid.exists())
        fixture_pid = int(observed_pid.read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(fixture_pid, 0)
        self.assertFalse(self.pid_file.exists())
        self.assertFalse(Path(f"{self.pid_file}.identity").exists())


if __name__ == "__main__":
    unittest.main()
