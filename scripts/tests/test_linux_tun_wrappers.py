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
SOAK_WRAPPER = ROOT / "scripts/ci/run-linux-tun-soak.sh"


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

    def run_soak_wrapper(
        self,
        *,
        targets: list[dict],
        profile: str = "smoke",
        iterations: str | None = None,
        host_os: str = "Linux",
        tun_device: str = "/dev/null",
    ) -> tuple[subprocess.CompletedProcess[str], str, str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bin_dir = root / "bin"
            bin_dir.mkdir()
            artifact_dir = root / "artifacts"
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
                "printf 'args=%s\\n' \"$*\" >> \"$FAKE_CARGO_LOG\"\n"
                "printf 'profile=%s iterations=%s\\n' \"${RIPDPI_SOAK_PROFILE:-}\" \"${RIPDPI_SOAK_ITERS:-}\" >> \"$FAKE_CARGO_LOG\"\n"
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
            env["RIPDPI_SOAK_PROFILE"] = profile
            env["RIPDPI_TUN_DEVICE"] = tun_device
            if iterations is None:
                env.pop("RIPDPI_SOAK_ITERS", None)
            else:
                env["RIPDPI_SOAK_ITERS"] = iterations
            completed = subprocess.run(
                ["bash", str(SOAK_WRAPPER), str(artifact_dir)],
                capture_output=True,
                check=False,
                env=env,
                text=True,
            )
            cargo_calls = cargo_log.read_text(encoding="utf-8") if cargo_log.exists() else ""
            profile_artifact = artifact_dir / "effective-profile.txt"
            profile_text = profile_artifact.read_text(encoding="utf-8") if profile_artifact.exists() else ""
            return completed, cargo_calls, profile_text

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

    def test_soak_smoke_profile_uses_50_iterations(self) -> None:
        completed, cargo_log, profile_text = self.run_soak_wrapper(
            targets=[{"name": "linux_tun_soak", "kind": ["test"]}],
            profile="smoke",
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("profile=smoke iterations=50", cargo_log)
        self.assertIn("--test linux_tun_soak soak_ -- --ignored --nocapture --test-threads=1", cargo_log)
        self.assertEqual(profile_text, "profile=smoke\niterations=50\n")

    def test_soak_full_profile_uses_500_iterations(self) -> None:
        completed, cargo_log, profile_text = self.run_soak_wrapper(
            targets=[{"name": "linux_tun_soak", "kind": ["test"]}],
            profile="full",
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("profile=full iterations=500", cargo_log)
        self.assertEqual(profile_text, "profile=full\niterations=500\n")

    def test_soak_iteration_override_is_recorded(self) -> None:
        completed, cargo_log, profile_text = self.run_soak_wrapper(
            targets=[{"name": "linux_tun_soak", "kind": ["test"]}],
            profile="full",
            iterations="7",
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("profile=full iterations=7", cargo_log)
        self.assertEqual(profile_text, "profile=full\niterations=7\n")

    def test_soak_rejects_unknown_profile_before_cargo(self) -> None:
        completed, cargo_log, _ = self.run_soak_wrapper(targets=[], profile="overnight")
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("unsupported RIPDPI_SOAK_PROFILE", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_soak_non_linux_host_fails_before_cargo(self) -> None:
        completed, cargo_log, _ = self.run_soak_wrapper(targets=[], host_os="Darwin")
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("requires a Linux host", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_soak_missing_tun_device_fails_before_cargo(self) -> None:
        completed, cargo_log, _ = self.run_soak_wrapper(
            targets=[],
            tun_device="/definitely/missing/ripdpi-tun",
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("Linux TUN device is unavailable", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_soak_rejects_non_positive_iteration_override(self) -> None:
        completed, cargo_log, _ = self.run_soak_wrapper(
            targets=[],
            iterations="0",
        )
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("positive integer", completed.stderr)
        self.assertEqual(cargo_log, "")

    def test_missing_soak_target_fails_instead_of_skipping(self) -> None:
        completed, _, _ = self.run_soak_wrapper(targets=[])
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("linux_tun_soak target is not present", completed.stderr)
        self.assertNotIn("skipping", completed.stdout + completed.stderr)

    def test_manifest_registers_separate_e2e_and_soak_targets(self) -> None:
        manifest = tomllib.loads(
            (ROOT / "native/rust/crates/ripdpi-tunnel-core/Cargo.toml").read_text(encoding="utf-8")
        )
        targets = {target["name"]: target["path"] for target in manifest.get("test", [])}
        self.assertEqual(targets["linux_tun_e2e"], "tests/linux_tun_e2e.rs")
        self.assertEqual(targets["linux_tun_soak"], "tests/linux_tun_soak.rs")


if __name__ == "__main__":
    unittest.main()
