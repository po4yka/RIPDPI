import subprocess
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class AndroidEmulatorImageTest(unittest.TestCase):
    def test_api_37_uses_published_major_minor_image_id(self):
        result = subprocess.run(
            ["bash", "-c", 'source scripts/ci/android-emulator-helpers.sh; android_image_version 37'],
            cwd=ROOT, text=True, capture_output=True,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("37.0", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
