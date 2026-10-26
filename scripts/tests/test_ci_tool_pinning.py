from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def ci_jobs(source: str) -> dict[str, str]:
    jobs = source.split("\njobs:\n", 1)[-1]
    return {
        match[1]: match[0]
        for match in re.finditer(r"(?ms)^  ([\w-]+):\n.*?(?=^  [\w-]+:\n|\Z)", jobs)
    }


class CiToolPinningTest(unittest.TestCase):
    def test_ci_does_not_wrap_rustc_with_sccache(self) -> None:
        # The GHA sccache backend measured 0% hits while Swatinem/rust-cache
        # already restores target/; it only added cache entries and latency.
        paths = [*(ROOT / ".github/workflows").glob("*.yml"), *(ROOT / ".github/actions").glob("*/action.yml")]
        for path in sorted(paths):
            with self.subTest(path=path.name):
                self.assertNotIn("sccache", path.read_text(encoding="utf-8").lower())

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
        self.assertIn('base64 --decode > "$bundle_path"', action)
        self.assertIn('test -s "$bundle_path"', action)

        # Secret-less (dependabot) pull_request runs materialize the committed
        # CI fixture bundle; every other event still requires the secret.
        self.assertIn(
            "if: inputs.embedded-relay-bundle-base64 == '' && github.event_name == 'pull_request'",
            action,
        )
        self.assertIn(
            "scripts/fixtures/embedded-relay-bundle/ci-fixture.json",
            action,
        )
        self.assertTrue(
            (ROOT / "scripts/fixtures/embedded-relay-bundle/ci-fixture.json").is_file(),
            "the CI fixture bundle must be committed next to the action that copies it",
        )

        secret_bundle = "${{ secrets.RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64 }}"
        simple_shard_bundle = (
            "${{ matrix.shard.experience == 'simple' && "
            "secrets.RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64 || '' }}"
        )
        for job_name, expected in (
            ("build-android-tests", secret_bundle),
            ("verify-roborazzi", secret_bundle),
            ("release-verification", simple_shard_bundle),
            ("android-instrumentation-apks", secret_bundle),
        ):
            with self.subTest(job_name=job_name):
                job = re.search(rf"(?ms)^  {job_name}:\n.*?(?=^  [\w-]+:\n|\Z)", ci)
                self.assertIsNotNone(job)
                assert job is not None
                self.assertIn(f"embedded-relay-bundle-base64: {expected}", job.group(0))

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
            "uses: taiki-e/install-action@1e29709c0ec63babbe8f266397a465f29a825f91",
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

    def test_gradle_cache_writers_have_disjoint_restore_namespaces(self) -> None:
        action = (ROOT / ".github/actions/setup-android-rust/action.yml").read_text()
        ci = (ROOT / ".github/workflows/ci.yml").read_text()
        default = re.search(
            r"(?ms)^  gradle-cache-key-prefix:\n.*?^    default: \"([^\"]+)\"", action
        )
        default_prefix = default[1] if default else "gradle-build-cache"
        self.assertEqual("gradle-build-cache", default_prefix)
        cache_steps = action.split(
            "    - name: Restore shared Gradle build cache (read-only)\n"
        )[1]
        keys = re.findall(r"(?m)^        key: (.+)$", cache_steps)
        restore_keys = re.findall(r"(?m)^          (.+-)$", cache_steps)
        self.assertEqual(2, len(keys))
        self.assertEqual(4, len(restore_keys))
        namespaces = {}
        for name, job in ci_jobs(ci).items():
            read_only = re.search(r"(?m)^          gradle-cache-read-only: (.+)$", job)
            if read_only is None or read_only[1] == '"true"':
                continue
            prefix = re.search(r"(?m)^          gradle-cache-key-prefix: (.+)$", job)
            prefix = prefix[1] if prefix else default_prefix
            shards = [
                dict(re.findall(r"(\w+): ([\w-]+)", entry))
                for entry in re.findall(r"(?m)^\s+- \{ (id: .+) \}$", job)
            ] + [
                {"id": shard_id}
                for shard_id in re.findall(r"(?m)^\s+- id: ([\w-]+)$", job)
            ]
            for shard in shards or [{}]:
                rendered = []
                for key in keys + restore_keys:
                    key = key.replace("${{ inputs.gradle-cache-key-prefix }}", prefix)
                    for field, value in shard.items():
                        key = key.replace(f"${{{{ matrix.shard.{field} }}}}", value)
                    rendered.append(key)
                self.assertNotIn("${{ matrix.", rendered[0])
                self.assertEqual(rendered[0], rendered[1])
                self.assertEqual(rendered[2:4], rendered[4:6])
                owner = namespaces.get(rendered[0], (name, rendered))[0]
                self.assertEqual(name, owner, f"{name} shares a writer key")
                namespaces[rendered[0]] = (name, rendered)
        self.assertIn("kotlin-coverage", {owner for owner, _ in namespaces.values()})
        self.assertIn(
            "pluggable-transport-assets",
            {owner for owner, _ in namespaces.values()},
        )
        candidate = (ROOT / ".github/workflows/release-candidate.yml").read_text()
        self.assertIn(
            "gradle-cache-key-prefix: gradle-pluggable-transport-build-cache\n",
            candidate,
        )
        for own_key, (owner, _) in namespaces.items():
            for other_owner, other in namespaces.values():
                if other[0] == own_key:
                    continue
                with self.subTest(writer=owner, other=other_owner):
                    self.assertFalse(any(own_key.startswith(key) for key in other[2:]))

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

        writers = {}
        for workflow in workflows:
            for name, job in ci_jobs(workflow.read_text(encoding="utf-8")).items():
                for value in re.findall(r"(?m)^\s+gradle-cache-read-only: ([^\n]+)$", job):
                    if value != '"true"':
                        writers[(workflow.name, name)] = value
        main_push = "${{ github.event_name != 'push' || github.ref != 'refs/heads/main' }}"
        self.assertEqual(
            {
                ("ci.yml", "kotlin-coverage"): "${{ github.ref != 'refs/heads/main' }}",
                ("ci.yml", "pluggable-transport-assets"): main_push,
                ("ci.yml", "gradle-static-analysis"): main_push,
                ("ci.yml", "build-android-debug"): main_push,
                ("ci.yml", "release-verification"): (
                    "${{ matrix.shard.experience != 'full' || "
                    "github.event_name != 'push' || github.ref != 'refs/heads/main' }}"
                ),
            },
            writers,
        )
        # Pull requests can restore caches written on main, so a writer must
        # never see the embedded relay bundle secret.
        for _, name in writers:
            with self.subTest(writer=name):
                bundle = re.findall(
                    r"(?m)^          embedded-relay-bundle-base64: (.+)$", ci_jobs(ci)[name]
                )
                if name == "release-verification":
                    self.assertEqual(
                        [
                            "${{ matrix.shard.experience == 'simple' && "
                            "secrets.RIPDPI_EMBEDDED_RELAY_BUNDLE_BASE64 || '' }}"
                        ],
                        bundle,
                    )
                else:
                    self.assertEqual([], bundle)


if __name__ == "__main__":
    unittest.main()
