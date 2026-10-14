import subprocess
import os
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class AndroidEmulatorImageTest(unittest.TestCase):
    def test_detached_start_requires_explicit_isolated_ci(self):
        with tempfile.TemporaryDirectory() as directory:
            avd = Path(directory) / "avd" / "keep-me.avd"
            avd.mkdir(parents=True)
            marker = avd / "config.ini"
            marker.write_text("preserve=true\n")
            for ci, flags in (("", []), ("", ["--fresh"]), ("true", [])):
                with self.subTest(ci=ci, flags=flags):
                    result = subprocess.run(
                        ["bash", "scripts/ci/start-android-emulator.sh", *flags,
                         "--avd", "keep-me", "--api", "37", "--arch", "arm64-v8a",
                         "--target", "google_apis"],
                        cwd=ROOT, env={**os.environ, "CI": ci, "ANDROID_AVD_HOME": str(avd.parent)},
                        text=True, capture_output=True,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("android-avd-pool.py", result.stderr)
                    self.assertEqual("preserve=true\n", marker.read_text())

    def test_ci_launcher_rejects_reserved_and_unsafe_names(self):
        for name in ("../other", "RIPDPI_Pool_1", "RIPDPI_Temp_owned", "."):
            with self.subTest(name=name):
                result = subprocess.run(
                    ["bash", "scripts/ci/start-android-emulator.sh", "--fresh",
                     "--avd", name, "--api", "37", "--arch", "arm64-v8a", "--target", "google_apis"],
                    cwd=ROOT, env={**os.environ, "CI": "true"}, text=True, capture_output=True,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("Unsafe or reserved", result.stderr)

    def test_api_37_uses_published_major_minor_image_id(self):
        result = subprocess.run(
            ["bash", "-c", 'source scripts/ci/android-emulator-helpers.sh; android_image_version 37'],
            cwd=ROOT, text=True, capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("37.0", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
