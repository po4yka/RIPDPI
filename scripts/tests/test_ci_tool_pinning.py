from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class CiToolPinningTest(unittest.TestCase):
    def test_compose_reports_require_explicit_gradle_opt_in(self) -> None:
        source = (
            ROOT
            / "build-logic/convention/src/main/kotlin/ripdpi.android.compose.gradle.kts"
        ).read_text(encoding="utf-8")

        self.assertIn('gradleProperty("ripdpi.composeReports")', source)
        self.assertIn(".map(String::toBooleanStrict)", source)
        self.assertNotIn('environmentVariable("CI")', source)

    def test_local_gradle_recipes_use_build_performance_fast_paths(self) -> None:
        source = (ROOT / "justfile").read_text(encoding="utf-8")

        self.assertIn(
            "build:\n    ./gradlew :app:assembleGithubFullDebug",
            source,
        )
        self.assertNotIn("build:\n    ./gradlew assembleDebug", source)
        for command in (
            "./gradlew testDebugUnitTest -Pripdpi.skipNativeBuild=true",
            "./gradlew :{{mod}}:testDebugUnitTest -Pripdpi.skipNativeBuild=true",
            './gradlew :{{mod}}:testDebugUnitTest --tests "{{class}}" '
            "-Pripdpi.skipNativeBuild=true",
            "./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true",
            "./gradlew coverageReport -Pripdpi.skipNativeBuild=true",
        ):
            self.assertIn(command, source)

    def test_rust_cache_keeps_host_target_as_the_default_mapping(self) -> None:
        source = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("rust-cache-workspaces:\n    description:", source)
        self.assertIn('default: "native/rust -> target"', source)
        self.assertIn(
            "workspaces: ${{ inputs.rust-cache-workspaces }}",
            source,
        )
        self.assertNotIn("workspaces: native/rust -> target", source)

    def test_android_native_toolchain_installs_pinned_cmake(self) -> None:
        action = (
            ROOT / ".github/actions/setup-android-rust/action.yml"
        ).read_text(encoding="utf-8")
        properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")

        self.assertIn("ripdpi.nativeCmakeVersion=3.31.6", properties)
        self.assertIn("steps.native-toolchain.outputs.cmake", action)
        self.assertIn('"cmake/${{ steps.native-toolchain.outputs.cmake }}"', action)
        self.assertIn("-cmake-${{ steps.native-toolchain.outputs.cmake }}-", action)
        self.assertIn('echo "sdk-root=$sdk_root" >> "$GITHUB_OUTPUT"', action)
        self.assertIn(
            "${{ steps.native-toolchain.outputs.sdk-root }}/cmake/"
            "${{ steps.native-toolchain.outputs.cmake }}",
            action,
        )
        self.assertIn(
            '[[ -x "$sdk_root/cmake/${{ steps.native-toolchain.outputs.cmake }}/bin/cmake" ]]',
            action,
        )
        self.assertNotIn(
            "steps.cache-android-sdk.outputs.cache-hit != 'true'",
            action,
        )

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
