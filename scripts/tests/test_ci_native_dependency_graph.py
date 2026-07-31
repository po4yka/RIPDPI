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
    def test_instrumented_tests_wait_only_for_x86_64_shard(self) -> None:
        self.assertIn(
            "needs: [change-routing, rust-native-x86_64]",
            job_source("android-instrumented-tests"),
        )

    def test_packaging_consumers_wait_for_complete_abi_set(self) -> None:
        expected_needs = "needs: [change-routing, rust-native-packaging, rust-native-x86_64]"
        self.assertIn(expected_needs, job_source("build-android-debug"))
        self.assertIn(
            "needs: [change-routing, rust-native-packaging, rust-native-x86_64, owned-stack-tls-fingerprint]",
            job_source("release-verification"),
        )

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


if __name__ == "__main__":
    unittest.main()
