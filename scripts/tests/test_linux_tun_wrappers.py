#!/usr/bin/env python3
"""Contract tests for the privileged Linux TUN CI wrappers."""
from __future__ import annotations

import json
import os
import subprocess
import tempfile
import tomllib
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
E2E_WRAPPER = ROOT / "scripts/ci/run-linux-tun-e2e.sh"


class LinuxTunWrapperTest(unittest.TestCase):
    def run_e2e_wrapper(
        self,
        *,
        targets: list[dict],
        host_os: str = "Linux",
        opt_in: str | None = "1",
        tun_device: str = "/dev/null",
    ) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            cargo_log = root / "cargo.log"
            metadata = {
                "packages": [
                    {
                        "name": "ripdpi-tunnel-core",
                        "targets": targets,
                    }
                ]
            }
            cargo = bin_dir / "cargo"
            cargo.write_text(
                "#!/usr/bin/env bash\n"
                "set -euo pipefail\n"
                "printf '%s\\n' \"$*\" >> \"$FAKE_CARGO_LOG\"\n"
                "if [ \"${1:-}\" = metadata ]; then\n"
                f"  printf '%s\\n' '{json.dumps(metadata)}'\n"
                "  exit 0\n"
                "fi\n"
                "exit 0\n",
                encoding="utf-8",
            )
            cargo.chmod(0o755)
            uname = bin_dir / "uname"
            uname.write_text(
                f"#!/usr/bin/env bash\nprintf '%s\\n' '{host_os}'\n",
                encoding="utf-8",
            )
            uname.chmod(0o755)
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            env["FAKE_CARGO_LOG"] = str(cargo_log)
            if opt_in is None:
                env.pop("RIPDPI_RUN_TUN_E2E", None)
            else:
                env["RIPDPI_RUN_TUN_E2E"] = opt_in
            env["RIPDPI_TUN_DEVICE"] = tun_device
            completed = subprocess.run(
                ["bash", str(E2E_WRAPPER)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )
            cargo_calls = cargo_log.read_text(encoding="utf-8") if cargo_log.exists() else ""
            return completed, cargo_calls

    def test_non_linux_host_fails_instead_of_skipping(self) -> None:
        completed, cargo_log = self.run_e2e_wrapper(targets=[], host_os="Darwin")
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("requires a Linux host", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_missing_explicit_opt_in_fails_before_cargo(self) -> None:
        completed, cargo_log = self.run_e2e_wrapper(targets=[], opt_in=None)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("RIPDPI_RUN_TUN_E2E=1 is required", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_missing_tun_device_fails_before_cargo(self) -> None:
        completed, cargo_log = self.run_e2e_wrapper(
            targets=[],
            tun_device="/definitely/missing/ripdpi-tun",
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("Linux TUN device is unavailable", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_missing_e2e_target_fails_instead_of_skipping(self) -> None:
        completed, _ = self.run_e2e_wrapper(targets=[])
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("linux_tun_e2e target is not present", completed.stderr)
        self.assertNotIn("skipping", completed.stdout + completed.stderr)

    def test_e2e_target_runs_only_real_e2e_tests_with_locked_cargo(self) -> None:
        completed, cargo_log = self.run_e2e_wrapper(
            targets=[{"name": "linux_tun_e2e", "kind": ["test"]}]
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("metadata --locked --manifest-path", cargo_log)
        self.assertIn("--test linux_tun_e2e e2e_ -- --ignored --nocapture", cargo_log)

    def test_manifest_registers_separate_e2e_and_soak_targets(self) -> None:
        manifest = tomllib.loads(
            (ROOT / "native/rust/crates/ripdpi-tunnel-core/Cargo.toml").read_text(encoding="utf-8")
        )
        targets = {target["name"]: target["path"] for target in manifest.get("test", [])}
        self.assertEqual(targets["linux_tun_e2e"], "tests/linux_tun_e2e.rs")
        self.assertEqual(targets["linux_tun_soak"], "tests/linux_tun_e2e.rs")


if __name__ == "__main__":
    unittest.main()
