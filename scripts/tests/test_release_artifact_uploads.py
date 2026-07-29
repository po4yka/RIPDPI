#!/usr/bin/env python3
"""Regression tests for release retrace and native-symbol uploads."""

from __future__ import annotations

import re
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = (
    ROOT / ".github/workflows/ci.yml",
    ROOT / ".github/workflows/release.yml",
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
    def test_ci_release_verification_uses_isolated_variant_matrix(self) -> None:
        source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        job = release_verification_job(source)
        self.assertIn("matrix:\n        variant:", job)
        self.assertEqual(6, job.count("id: "))
        self.assertIn('./gradlew "${{ matrix.variant.task }}" "${common_args[@]}"', job)
        self.assertNotIn("for task in \\", job)

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
        self.assertIn("name: release-retrace-inputs-${{ matrix.variant.id }}", ci_source)

    def test_native_symbol_bundle_uses_one_shared_fail_closed_packager(self) -> None:
        ci_source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        release_source = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")

        self.assertEqual(1, ci_source.count("name: release-native-symbols\n"))
        self.assertIn("matrix.variant.id == 'github-full'", ci_source)
        for source in (ci_source, release_source):
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

    def test_release_sboms_use_single_scoped_syft_inventories(self) -> None:
        source = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")

        self.assertEqual(1, source.count("anchore/syft/main/install.sh"))
        self.assertNotIn("cargo cyclonedx", source)
        rust_block = upload_block(source, "Generate Rust SBOM")
        self.assertIn(
            'syft packages dir:native/rust --output "cyclonedx-json=$GITHUB_WORKSPACE/rust-bom.json"',
            rust_block,
        )
        android_block = upload_block(source, "Generate Android SBOM")
        self.assertIn(
            'syft packages dir:. --output "cyclonedx-json=$GITHUB_WORKSPACE/android-bom.json"',
            android_block,
        )

    def test_release_evidence_handoff_is_private_and_fail_closed(self) -> None:
        evidence_source = (
            ROOT / ".github/workflows/dns-ipv6-killswitch-evidence.yml"
        ).read_text(encoding="utf-8")
        release_source = (ROOT / ".github/workflows/release.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn(
            "ORDINARY_RESULTS_BASE64: "
            "${{ secrets.RIPDPI_ANDROID_ORDINARY_RESULTS_BASE64 }}",
            evidence_source,
        )
        self.assertIn(
            "printf '%s' \"$ORDINARY_RESULTS_BASE64\" | base64 --decode",
            evidence_source,
        )
        self.assertIn(
            "--expected-evidence-run-id \"$GITHUB_RUN_ID\"",
            evidence_source,
        )
        self.assertIn(
            'test "$results_name" = ordinary-results.json', release_source
        )
        self.assertIn(
            'results_path="$RUNNER_TEMP/dns-ipv6-killswitch-release-evidence/'
            '$results_name"',
            release_source,
        )
        self.assertNotIn("eval ", release_source)


if __name__ == "__main__":
    unittest.main()
