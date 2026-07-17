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

    def test_native_symbol_uploads_are_variant_aware_and_fail_closed(self) -> None:
        for workflow in WORKFLOWS:
            block = upload_block(
                workflow.read_text(encoding="utf-8"),
                "Upload release native symbols",
            )
            self.assertIn(
                "app/build/intermediates/native_symbol_tables/*Release/*/out/**",
                block,
            )
            self.assertIn("if-no-files-found: error", block)

        ci_source = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn("name: release-native-symbols-${{ matrix.variant.id }}", ci_source)


if __name__ == "__main__":
    unittest.main()
