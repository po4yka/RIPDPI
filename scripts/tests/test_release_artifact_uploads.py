#!/usr/bin/env python3
"""Regression tests for release builds, retrace inputs, symbols, and SBOMs."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/release-candidate.yml",
)


def upload_block(source: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^\s*- name: {re.escape(name)}$.*?(?=^\s*- name:|^\s{{2}}[\w-]+:|\Z)",
        source,
    )
    if match is None:
        raise AssertionError(f"missing upload step: {name}")
    return match.group(0)


def release_verification_job(source: str) -> str:
    match = re.search(r"(?ms)^  release-verification:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError("missing release-verification CI job")
    return match.group(0)


class ReleaseArtifactUploadsTest(unittest.TestCase):
    def test_ci_release_verification_uses_measured_two_variant_shards(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        job = release_verification_job(source)
        self.assertIn("matrix:\n        shard:", job)
        self.assertEqual(3, job.count("id: "))
        shards = re.findall(
            r"(?m)^          - \{ id: ([\w-]+), tasks: '([^']+)' \}$", job
        )
        self.assertEqual(3, len(shards))
        self.assertTrue(
            all(len(tasks.split(",")) == 2 for _, tasks in shards),
            "each release shard must contain exactly two builds",
        )
        for task in (
            ":app:assembleFdroidFullRelease",
            ":app:assembleFdroidSimpleRelease",
            ":app:assembleGithubFullRelease",
            ":app:assembleGithubSimpleRelease",
            ":app:assemblePlayFullRelease",
            ":app:assemblePlaySimpleRelease",
        ):
            self.assertEqual(1, len(re.findall(rf"{re.escape(task)}(?![A-Za-z])", job)))
        self.assertIn("IFS=',' read -r -a release_tasks", job)
        self.assertIn('for task in "${release_tasks[@]}"', job)
        self.assertIn("printf '### %s release build", job)
        self.assertIn("./gradlew --stop", job)
        self.assertIn(
            "needs: [change-routing, rust-native-packaging, rust-native-x86_64, "
            "owned-stack-tls-fingerprint, pluggable-transport-assets, xray-native]",
            job,
        )
        self.assertNotIn("Check owned-stack TLS fingerprint snapshot", job)

    def test_ci_tls_fingerprint_gate_runs_once_before_release_shards(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertEqual(1, source.count("Check owned-stack TLS fingerprint snapshot"))
        tls_job = re.search(
            r"(?ms)^  owned-stack-tls-fingerprint:\n.*?(?=^  [\w-]+:\n|\Z)",
            source,
        )
        self.assertIsNotNone(tls_job)
        assert tls_job is not None
        self.assertEqual(
            1,
            tls_job.group(0).count("Check owned-stack TLS fingerprint snapshot"),
        )
        self.assertIn(
            "bash scripts/ci/check-owned-stack-tls-fingerprint.sh",
            tls_job.group(0),
        )
        self.assertIn('skip-android-sdk-ndk: "true"', tls_job.group(0))

    def test_ci_builds_release_instrumentation_without_signing_secrets(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        job = release_verification_job(source)
        build_step = re.search(
            r"(?ms)^      - name: Verify \$\{\{ matrix\.shard\.id \}\} release shard with prebuilt natives\n.*?(?=^      - name:|^      - uses:|\Z)",
            job,
        )
        self.assertIsNotNone(build_step)
        assert build_step is not None
        release_test = build_step.group(0)
        self.assertIn('if [[ "${{ matrix.shard.id }}" == github ]]', release_test)
        self.assertIn(":app:assembleGithubFullReleaseAndroidTest", release_test)
        self.assertIn("-Pripdpi.testBuildType=release", release_test)
        self.assertEqual(2, release_test.count('"${common_args[@]}"'))
        self.assertIn("test \"${#test_apks[@]}\" -eq 1", release_test)
        self.assertNotIn("RIPDPI_SIGNING_", release_test)
        self.assertNotIn("KEYSTORE_", release_test)

    def test_retrace_uploads_are_variant_aware_and_fail_closed(self) -> None:
        for workflow in WORKFLOWS:
            block = upload_block(
                workflow.read_text(encoding="utf-8"),
                "Upload release retrace inputs",
            )
            self.assertIn("app/build/outputs/mapping/*Release/mapping.txt", block)
            self.assertIn(
                "app/build/intermediates/compose_mapping/*Release/compose-mapping.txt",
                block,
            )
            self.assertIn("if-no-files-found: error", block)

        ci_source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("name: release-retrace-inputs-${{ matrix.shard.id }}", ci_source)

    def test_native_symbol_bundle_uses_one_shared_fail_closed_packager(self) -> None:
        ci_source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        candidate_source = (
            ROOT / ".github/workflows/release-candidate.yml"
        ).read_text(encoding="utf-8")

        self.assertEqual(1, ci_source.count("name: release-native-symbols\n"))
        self.assertIn("matrix.shard.id == 'github'", ci_source)
        for source in (ci_source, candidate_source):
            self.assertIn("python3 scripts/ci/package_native_symbols.py", source)
            block = upload_block(source, "Upload release native symbols")
            self.assertIn("release-native-symbols/manifest.json", block)
            self.assertIn("release-native-symbols/release-native-symbols.zip", block)
            self.assertIn("if-no-files-found: error", block)
            self.assertNotIn("native_symbol_tables", source)

    def test_native_producers_upload_separate_symbol_sidecars(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertEqual(2, source.count("Upload native symbol sidecar"))
        self.assertIn("name: native-symbols-${{ matrix.abi.name }}", source)
        self.assertIn("name: native-symbols-x86_64", source)
        self.assertIn("if-no-files-found: error", source)

    def test_release_sboms_use_pinned_syft_outside_signing_job(self) -> None:
        source = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")

        self.assertNotIn("anchore/syft/main/install.sh", source)
        self.assertNotIn("cargo cyclonedx", source)
        self.assertIn("readonly syft_version=1.50.0", source)
        self.assertIn("sha256sum --check --strict", source)
        self.assertIn('"dir:$RUNNER_TEMP/release-candidate"', source)
        self.assertIn("dir:native/rust", source)


if __name__ == "__main__":
    unittest.main()
