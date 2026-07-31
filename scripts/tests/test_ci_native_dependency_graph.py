from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"


def job_source(name: str) -> str:
    source = CI_WORKFLOW.read_text(encoding="utf-8")
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError(f"missing {name} CI job")
    return match.group(0)


class NativeDependencyGraphTest(unittest.TestCase):
    def test_native_producers_cache_the_gradle_cargo_target_per_abi(self) -> None:
        expected_mapping = (
            'rust-cache-workspaces: "native/rust -> '
            '../../core/engine/build/intermediates/rust-native-libs"'
        )

        packaging = job_source("rust-native-packaging")
        self.assertIn(expected_mapping, packaging)
        self.assertIn(
            "rust-cache-shared-key: rust-android-${{ matrix.abi.name }}",
            packaging,
        )
        self.assertIn(
            "rust-cache-save: ${{ github.ref == 'refs/heads/main' }}",
            packaging,
        )

        x86_64 = job_source("rust-native-x86_64")
        self.assertIn(expected_mapping, x86_64)
        self.assertIn("rust-cache-shared-key: rust-android-x86_64", x86_64)
        self.assertIn(
            "rust-cache-save: ${{ github.ref == 'refs/heads/main' }}",
            x86_64,
        )

        self.assertNotIn(
            "rust-cache-workspaces:",
            job_source("rust-cross-check"),
        )

    def test_instrumented_tests_wait_for_x86_64_and_verified_pt_assets(self) -> None:
        self.assertIn(
            "needs: [change-routing, rust-native-x86_64, pluggable-transport-assets]",
            job_source("android-instrumented-tests"),
        )

    def test_packaging_consumers_wait_for_complete_abi_set(self) -> None:
        expected_needs = (
            "needs: [change-routing, rust-native-packaging, rust-native-x86_64, "
            "pluggable-transport-assets]"
        )
        self.assertIn(expected_needs, job_source("build-android-debug"))
        self.assertIn(
            "needs: [change-routing, rust-native-packaging, rust-native-x86_64, "
            "owned-stack-tls-fingerprint, pluggable-transport-assets]",
            job_source("release-verification"),
        )

    def test_packaging_consumers_run_after_intentional_ancestor_skips(self) -> None:
        expected_results = {
            "build-android-debug": (
                "needs.rust-native-packaging.result == 'success'",
                "needs.rust-native-x86_64.result == 'success'",
                "needs.pluggable-transport-assets.result == 'success'",
            ),
            "release-verification": (
                "needs.rust-native-packaging.result == 'success'",
                "needs.rust-native-x86_64.result == 'success'",
                "needs.owned-stack-tls-fingerprint.result == 'success'",
                "needs.pluggable-transport-assets.result == 'success'",
            ),
            "android-instrumented-tests": (
                "needs.rust-native-x86_64.result == 'success'",
                "needs.pluggable-transport-assets.result == 'success'",
            ),
        }

        for job_name, result_guards in expected_results.items():
            with self.subTest(job=job_name):
                source = job_source(job_name)
                self.assertIn("!cancelled()", source)
                for result_guard in result_guards:
                    self.assertIn(result_guard, source)

    def test_debug_apks_build_in_independent_distribution_shards(self) -> None:
        source = job_source("build-android-debug")

        for shard_id, task in (
            ("github", ":app:assembleGithubFullDebug"),
            ("fdroid", ":app:assembleFdroidFullDebug"),
            ("play", ":app:assemblePlayFullDebug"),
        ):
            self.assertIn(f"id: {shard_id}, task: '{task}'", source)
        self.assertIn('./gradlew "${{ matrix.shard.task }}"', source)
        self.assertIn(
            "name: native-size-report-debug-${{ matrix.shard.id }}",
            source,
        )
        self.assertIn('setup-rust: "false"', source)
        self.assertNotIn("./gradlew \\\n+            :app:assembleGithubFullDebug", source)

    def test_packaging_matrix_excludes_x86_64(self) -> None:
        packaging = job_source("rust-native-packaging")
        self.assertNotIn("x86_64-linux-android", packaging)
        self.assertIn("rust-native-x86_64", CI_WORKFLOW.read_text(encoding="utf-8"))

    def test_each_native_shard_merges_all_helper_asset_outputs(self) -> None:
        expected_assets = (
            ("rootHelperAssets", "ripdpi-root-helper"),
            ("naiveProxyAssets", "ripdpi-naiveproxy"),
            ("cloudflareOriginAssets", "ripdpi-cloudflare-origin"),
        )

        for job_name, abi in (
            ("rust-native-packaging", "${{ matrix.abi.name }}"),
            ("rust-native-x86_64", "x86_64"),
        ):
            with self.subTest(job=job_name):
                source = job_source(job_name)
                for output_dir, executable in expected_assets:
                    expected_call = (
                        "stage_asset \\\n"
                        f'            "core/engine/build/generated/{output_dir}/bin/{abi}" \\\n'
                        f'            "{executable}" "$assets_dir"'
                    )
                    self.assertIn(expected_call, source)
                self.assertIn('[ ! -f "$source_dir/$executable" ]', source)
                self.assertIn('cp -a "$source_dir/." "$destination_dir/"', source)
                self.assertIn('chmod 0755 "$destination_dir/$executable"', source)
                self.assertIn('[ ! -x "$destination_dir/$executable" ]', source)

    def test_packaging_consumers_read_each_helper_from_merged_assets(self) -> None:
        expected_properties = (
            "ripdpi.prebuiltRootHelperDir",
            "ripdpi.prebuiltNaiveProxyDir",
            "ripdpi.prebuiltCloudflareOriginDir",
        )

        for job_name, assets_dir in (
            ("build-android-debug", "$RUNNER_TEMP/prebuilt/assetsBin"),
            ("release-verification", "$RUNNER_TEMP/prebuilt-release/assetsBin"),
            (
                "android-instrumented-tests",
                "$RUNNER_TEMP/prebuilt-integration/assetsBin",
            ),
        ):
            with self.subTest(job=job_name):
                source = job_source(job_name)
                for property_name in expected_properties:
                    self.assertIn(f'-P{property_name}="{assets_dir}"', source)

    def test_prebuilt_native_consumers_skip_unused_rust_setup(self) -> None:
        for job_name in ("build-android-debug", "android-instrumented-tests"):
            with self.subTest(job=job_name):
                source = job_source(job_name)
                self.assertIn('setup-rust: "false"', source)
                self.assertIn('setup-sccache: "false"', source)
                self.assertIn('setup-android-ndk: "false"', source)
                self.assertNotIn("RUSTC_WRAPPER: sccache", source)

        release = job_source("release-verification")
        self.assertIn('setup-rust: "false"', release)
        self.assertIn('setup-sccache: "false"', release)
        self.assertIn("setup-android-ndk: ${{ matrix.shard.id == 'github' }}", release)
        self.assertNotIn("RUSTC_WRAPPER: sccache", release)

    def test_pt_producer_is_source_only_and_consumers_bind_manifest_digest(self) -> None:
        producer = job_source("pluggable-transport-assets")
        self.assertIn(":core:engine:buildPluggableTransportAssets", producer)
        self.assertIn("-Pripdpi.pluggableTransportAssetsMode=source", producer)
        self.assertIn("-Pripdpi.pluggableTransportAssetsStrictFailures=true", producer)
        self.assertIn("github.event.schedule == '11 4 * * 4'", producer)
        self.assertIn("github.event.schedule == '27 4 * * 5'", producer)
        self.assertIn("manifest_sha256", producer)
        self.assertIn("name: pluggable-transport-assets", producer)
        self.assertIn("pluggable-transport-assets.tar.gz", producer)

        for job_name in (
            "build-android-debug",
            "release-verification",
            "android-instrumented-tests",
        ):
            with self.subTest(job=job_name):
                source = job_source(job_name)
                self.assertIn("name: pluggable-transport-assets", source)
                self.assertIn("ripdpi.prebuiltPluggableTransportAssetsDir", source)
                self.assertIn(
                    "ripdpi.prebuiltPluggableTransportAssetsManifestSha256",
                    source,
                )
                self.assertIn(
                    "needs.pluggable-transport-assets.outputs.manifest_sha256",
                    source,
                )
                self.assertIn("Extract verified pluggable transport assets", source)
                self.assertIn("pluggable-transport-assets.tar.gz", source)

    def test_every_android_asset_consumer_uses_the_pt_producer(self) -> None:
        workflow_consumers = {
            "phase0-baseline": "needs: [change-routing, pluggable-transport-assets]",
            "android-macrobenchmark": "needs: [change-routing, pluggable-transport-assets]",
            "android-network-e2e": "needs: [change-routing, pluggable-transport-assets]",
            "android-journeys": "needs: [change-routing, pluggable-transport-assets]",
            "android-relay-emulator-smoke": "needs: [change-routing, pluggable-transport-assets]",
        }
        for job_name, expected_needs in workflow_consumers.items():
            with self.subTest(job=job_name):
                source = job_source(job_name)
                self.assertIn(expected_needs, source)
                self.assertIn("Download verified pluggable transport assets", source)
                self.assertIn("Extract verified pluggable transport assets", source)
                self.assertIn(
                    "needs.pluggable-transport-assets.outputs.manifest_sha256",
                    source,
                )


if __name__ == "__main__":
    unittest.main()
