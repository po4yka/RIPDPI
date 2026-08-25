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
        action = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(
            encoding="utf-8"
        )
        properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")

        self.assertIn("ripdpi.nativeCmakeVersion=3.31.6", properties)
        self.assertIn("steps.native-toolchain.outputs.cmake", action)
        self.assertIn('"cmake/${{ steps.native-toolchain.outputs.cmake }}"', action)
        self.assertIn("-cmake-${{ steps.native-toolchain.outputs.cmake }}-", action)
        self.assertNotIn(
            "steps.cache-android-sdk.outputs.cache-hit != 'true'",
            action,
        )
        self.assertIn(
            "a ~/.android cache hit is not installation evidence",
            action,
        )

    def test_ci_materializes_private_bundle_for_every_simple_variant_gate(self) -> None:
        action = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(
            encoding="utf-8"
        )
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")

        self.assertIn("embedded-relay-bundle-base64:\n    description:", action)
        self.assertIn("base64 --decode > \"$bundle_path\"", action)
        self.assertIn('test -s "$bundle_path"', action)

        for job_name in (
            "build-android-tests",
            "verify-roborazzi",
            "release-verification",
            "android-instrumented-tests",
        ):
            with self.subTest(job_name=job_name):
                job = re.search(rf"(?ms)^  {job_name}:\n.*?(?=^  [\w-]+:\n|\Z)", ci)
                self.assertIsNotNone(job)
                assert job is not None
                self.assertIn(
                    "embedded-relay-bundle-base64: "
                    "${{ secrets.RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64 }}",
                    job.group(0),
                )

    def test_jni_symbol_guard_uses_shared_android_rust_setup(self) -> None:
        source = (ROOT / ".github/workflows/jni-symbol-diff.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("uses: ./.github/actions/setup-android-rust", source)
        self.assertNotIn('sdkmanager "ndk;', source)

    def test_jni_symbol_guard_covers_every_android_cdylib(self) -> None:
        source = (ROOT / ".github/workflows/jni-symbol-diff.yml").read_text(
            encoding="utf-8"
        )

        libraries = {
            "ripdpi": "ripdpi-android",
            "ripdpi-tunnel": "ripdpi-tunnel-android",
            "ripdpi-relay": "ripdpi-relay-android",
            "ripdpi-warp": "ripdpi-warp-android",
            "ripdpi-amneziawg": "ripdpi-amneziawg-android",
        }
        for library, crate in libraries.items():
            with self.subTest(library=library):
                self.assertIn(f"{library} {crate}", source)
                self.assertIn(f'"native/rust/crates/{crate}/**"', source)
                self.assertTrue(
                    (
                        ROOT
                        / "native"
                        / "rust"
                        / "crates"
                        / crate
                        / "jni-symbols.baseline"
                    ).is_file(),
                    f"missing JNI symbol baseline for {library}",
                )

    def test_github_actions_do_not_execute_floating_rust_toolchain_action(self) -> None:
        sources = [ROOT / ".github/actions/setup-android-rust/action.yml"]
        sources.extend(sorted((ROOT / ".github/workflows").glob("*.yml")))
        for path in sources:
            self.assertNotIn(
                "dtolnay/rust-toolchain@master", path.read_text(encoding="utf-8"), path
            )

    def test_fuzz_nightly_uses_prebuilt_pinned_cargo_fuzz(self) -> None:
        source = (ROOT / ".github/workflows/fuzz-nightly.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn(
            "uses: taiki-e/install-action@ba47c86ac325773530516bb756137ac718732518",
            source,
        )
        self.assertIn("tool: cargo-fuzz@0.13.1", source)
        self.assertNotIn("install cargo-fuzz", source)

    def test_android_cli_uses_versioned_digest_verified_binary(self) -> None:
        source = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn('android_cli_version="1.0.15857036"', source)
        self.assertIn('android_cli_sha256="e5b6930e', source)
        self.assertIn("linux_x86_64/android-cli", source)
        self.assertIn("sha256sum --check --strict", source)
        self.assertIn('version_output="$(android --no-metrics --version 2>&1)"', source)
        self.assertNotIn("android --version 2>&1 | head", source)
        self.assertNotIn("android/cli/latest", source)
        self.assertNotIn("android update", source)
        self.assertNotIn("android init", source)

    def test_gradle_build_cache_writers_are_trusted_main_only(self) -> None:
        action = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text(
            encoding="utf-8"
        )
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))

        self.assertIn("gradle-cache-read-only:\n    description:", action)
        self.assertIn('default: "true"', action)
        self.assertNotIn(
            "gradle-cache-read-only: ${{ github.event_name == 'pull_request' }}",
            ci,
        )

        writer = re.search(r"(?ms)^  kotlin-coverage:\n.*?(?=^  [\w-]+:\n|\Z)", ci)
        self.assertIsNotNone(writer)
        assert writer is not None
        self.assertIn(
            "gradle-cache-read-only: ${{ github.ref != 'refs/heads/main' }}",
            writer.group(0),
        )
        pt_writer = re.search(
            r"(?ms)^  pluggable-transport-assets:\n.*?(?=^  [\w-]+:\n|\Z)", ci
        )
        self.assertIsNotNone(pt_writer)
        assert pt_writer is not None
        self.assertIn(
            "gradle-cache-read-only: ${{ github.event_name != 'push' || github.ref != 'refs/heads/main' }}",
            pt_writer.group(0),
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
            [
                (
                    "ci.yml",
                    "${{ github.event_name != 'push' || github.ref != 'refs/heads/main' }}",
                ),
                ("ci.yml", "${{ github.ref != 'refs/heads/main' }}"),
            ],
            writable,
        )


if __name__ == "__main__":
    unittest.main()
