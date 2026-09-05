from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import tomllib
import unittest
from pathlib import Path

from scripts.ci.resolve_change_routing import routing_outputs


ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = ROOT / ".github/workflows/ci.yml"


def job_source(name: str) -> str:
    source = CI_WORKFLOW.read_text(encoding="utf-8")
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError(f"missing {name} CI job")
    return match.group(0)


class NativeDependencyGraphTest(unittest.TestCase):
    def test_instrumentation_consumers_use_one_verified_apk_bundle(self) -> None:
        source = job_source("android-instrumented-tests")
        self.assertNotIn("./gradlew", source)
        self.assertIn('setup-gradle: "false"', source)
        self.assertIn("needs: [change-routing, android-instrumentation-apks]", source)
        self.assertIn("needs.android-instrumentation-apks.result == 'success'", source)
        self.assertIn("name: android-instrumentation-apks", source)
        self.assertEqual(4, source.count("prebuilt_android_instrumentation.py run"))
        self.assertEqual(4, source.count('--sha "$GITHUB_SHA"'))
        for api in (27, 33, 35, 36, 37):
            self.assertIn(f"api: {api}\n", source)
        producer = job_source("android-instrumentation-apks")
        self.assertNotIn("matrix:", producer)
        self.assertEqual(1, producer.count(":app:stageCiInstrumentationApks"))
        self.assertIn("-Pripdpi.enableAbiSplits=false", producer)
        self.assertIn("prebuilt_android_instrumentation.py seal", producer)
        required = job_source("ci-required")
        self.assertIn("      - android-instrumentation-apks\n", required)
        self.assertIn('"android-instrumentation-apks",', required)

    def test_xray_bootstrap_preserves_both_installed_tool_pins(self) -> None:
        action = (ROOT / ".github/actions/build-xray/action.yml").read_text()
        bootstrap = re.search(
            r"(?ms)^    - name: Install pinned Go mobile toolchain\n.*?^      run: \|\n"
            r"((?:^        [^\n]*\n|^\n)+)", action,
        )
        self.assertIsNotNone(bootstrap)
        version = "v" + tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]["gomobile"]
        with tempfile.TemporaryDirectory(prefix="xray bootstrap ") as temporary:
            directory = Path(temporary)
            tools = directory / "tools"
            tools.mkdir()
            for name in ("bash", "sed", "python3"):
                executable = shutil.which(name)
                self.assertIsNotNone(executable, name)
                (tools / name).symlink_to(executable)
            # Executable bootstrap fixture: upstream init installs gobind@latest.
            # Actual Go/Android compilation is covered by the native producer.
            fake_go = tools / "go"
            fake_go.write_text(f"#!{sys.executable}\n" + textwrap.dedent('''\
                import os
                from pathlib import Path
                import sys

                if len(sys.argv) != 3 or sys.argv[1] != "install":
                    sys.exit(90)
                package, version = sys.argv[2].rsplit("@", 1)
                name = package.rsplit("/", 1)[1]
                if name not in ("gomobile", "gobind"):
                    sys.exit(91)
                destination = Path(os.environ["GOBIN"])
                destination.mkdir(parents=True, exist_ok=True)
                (destination / (name + ".version")).write_text(version)
                executable = destination / name
                executable.write_text(
                    "#!/usr/bin/env bash\\nset -euo pipefail\\n"
                    'test "$1" = init\\n'
                    "go install golang.org/x/mobile/cmd/gobind@latest\\n"
                )
                executable.chmod(0o755)
                '''))
            fake_go.chmod(0o755)
            environment = dict(os.environ, PATH=str(tools),
                               RUNNER_TEMP=str(directory), ANDROID_SDK_ROOT=str(directory / "sdk"),
                               GITHUB_PATH=str(directory / "github-path"),
                               GITHUB_ENV=str(directory / "github-env"),
                               GOTOOLCHAIN="go1.27.0", GOSUMDB="sum.golang.org",
                               GOPROXY="https://proxy.golang.org,direct")
            result = subprocess.run(["bash", "--noprofile", "--norc", "-c", textwrap.dedent(bootstrap[1])],
                                    cwd=ROOT, env=environment, capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            for tool in ("gomobile", "gobind"):
                self.assertEqual(version, (directory / "xray-go-bin" / (tool + ".version")).read_text(), tool)
            self.assertEqual(f"{directory / 'xray-go-bin'}\n", (directory / "github-path").read_text())

    def test_xray_sources_and_recipe_trigger_full_ci(self) -> None:
        for path in ("native/xray/patches/libxray-managed-runtime.patch",
                     "scripts/native/build-libxray.sh", ".github/actions/build-xray/action.yml"):
            with self.subTest(path=path):
                outputs = routing_outputs([path])
                self.assertEqual("true", outputs["run_full_ci"])

    def test_xray_producer_and_linked_lane_are_required(self) -> None:
        producer = job_source("xray-native")
        self.assertIn("uses: ./.github/actions/build-xray", producer)
        self.assertIn("name: xray-native", producer)
        linked = job_source("xray-linked-unit-tests")
        self.assertIn("xray-native]", linked)
        self.assertIn("native/xray/artifacts", linked)
        self.assertIn(":core:engine:testDebugUnitTest", linked)
        self.assertIn(":core:service:testDebugUnitTest", linked)
        required = job_source("ci-required")
        for job in ("xray-native", "xray-linked-unit-tests"):
            self.assertIn(f"      - {job}\n", required)
            self.assertIn(f'"{job}",', required)

    def test_every_apk_consumer_downloads_verified_xray(self) -> None:
        for name in ("build-android-debug", "release-verification", "android-instrumentation-apks",
                     "phase0-baseline", "android-macrobenchmark", "android-network-e2e",
                     "android-journeys", "android-relay-emulator-smoke"):
            with self.subTest(job=name):
                source = job_source(name)
                self.assertIn("xray-native]", source)
                self.assertIn("name: xray-native", source)
                self.assertIn("path: native/xray/artifacts", source)
                self.assertIn("verify-libxray-artifacts.sh --release", source)
        candidate = (ROOT / ".github/workflows/release-candidate.yml").read_text()
        self.assertIn("needs: [pluggable-transport-assets, xray-native]", candidate)
        self.assertIn("uses: ./.github/actions/build-xray", candidate)
        self.assertIn("path: native/xray/artifacts", candidate)
        self.assertIn("verify-libxray-artifacts.sh --release", candidate)

    def test_xray_action_builds_pinned_sources_without_checksum_bypass(self) -> None:
        action = (ROOT / ".github/actions/build-xray/action.yml").read_text()
        self.assertIn("GOTOOLCHAIN: go1.27.0", action)
        self.assertIn("GOSUMDB: sum.golang.org", action)
        self.assertIn("gradle/libs.versions.toml", action)
        self.assertIn("ripdpi.nativeNdkVersion", action)
        self.assertIn("bash scripts/native/build-libxray.sh", action)
        self.assertIn("verify-libxray-artifacts.sh --release", action)
        self.assertIn("scripts.tests.test_libxray_artifacts", action)
        self.assertNotIn("GOSUMDB=off", action)

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
            "needs: [change-routing, rust-native-x86_64, pluggable-transport-assets, xray-native]",
            job_source("android-instrumentation-apks"),
        )

    def test_packaging_consumers_wait_for_complete_abi_set(self) -> None:
        expected_needs = (
            "needs: [change-routing, rust-native-packaging, rust-native-x86_64, "
            "pluggable-transport-assets, xray-native]"
        )
        self.assertIn(expected_needs, job_source("build-android-debug"))
        self.assertIn(
            "needs: [change-routing, rust-native-packaging, rust-native-x86_64, "
            "owned-stack-tls-fingerprint, pluggable-transport-assets, xray-native]",
            job_source("release-verification"),
        )

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
                "android-instrumentation-apks",
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
            "android-instrumentation-apks",
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
            "phase0-baseline": "needs: [change-routing, pluggable-transport-assets, xray-native]",
            "android-macrobenchmark": "needs: [change-routing, pluggable-transport-assets, xray-native]",
            "android-network-e2e": "needs: [change-routing, pluggable-transport-assets, xray-native]",
            "android-journeys": "needs: [change-routing, pluggable-transport-assets, xray-native]",
            "android-relay-emulator-smoke": "needs: [change-routing, pluggable-transport-assets, xray-native]",
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
