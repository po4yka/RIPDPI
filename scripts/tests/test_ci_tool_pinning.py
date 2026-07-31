from __future__ import annotations

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class CiToolPinningTest(unittest.TestCase):
    def test_github_actions_do_not_execute_floating_rust_toolchain_action(self) -> None:
        sources = [ROOT / ".github/actions/setup-android-rust/action.yml"]
        sources.extend(sorted((ROOT / ".github/workflows").glob("*.yml")))
        for path in sources:
            self.assertNotIn("dtolnay/rust-toolchain@master", path.read_text(encoding="utf-8"), path)

    def test_android_cli_uses_versioned_digest_verified_binary(self) -> None:
        source = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(encoding="utf-8")
        self.assertIn("android_cli_version=\"1.0.15857036\"", source)
        self.assertIn("android_cli_sha256=\"e5b6930e", source)
        self.assertIn("linux_x86_64/android-cli", source)
        self.assertIn("sha256sum --check --strict", source)
        self.assertIn('version_output="$(android --no-metrics --version 2>&1)"', source)
        self.assertNotIn("android --version 2>&1 | head", source)
        self.assertNotIn("android/cli/latest", source)
        self.assertNotIn("android update", source)
        self.assertNotIn("android init", source)


if __name__ == "__main__":
    unittest.main()
