from __future__ import annotations

import re
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

    def test_kotlin_coverage_is_the_only_gradle_build_cache_writer(self) -> None:
        action = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(
            encoding="utf-8"
        )
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))

        self.assertIn('gradle-cache-read-only:\n    description:', action)
        self.assertIn('default: "true"', action)
        self.assertNotIn(
            "gradle-cache-read-only: ${{ github.event_name == 'pull_request' }}",
            ci,
        )

        writer = re.search(
            r"(?ms)^  kotlin-coverage:\n.*?(?=^  [\w-]+:\n|\Z)", ci
        )
        self.assertIsNotNone(writer)
        assert writer is not None
        self.assertIn(
            "gradle-cache-read-only: ${{ github.ref != 'refs/heads/main' }}",
            writer.group(0),
        )

        writable = []
        for workflow in workflows:
            for value in re.findall(
                r"(?m)^\s+gradle-cache-read-only: ([^\n]+)$",
                workflow.read_text(encoding="utf-8"),
            ):
                if value != '"true"':
                    writable.append((workflow.name, value))
        self.assertEqual(
            [("ci.yml", "${{ github.ref != 'refs/heads/main' }}")],
            writable,
        )


if __name__ == "__main__":
    unittest.main()
