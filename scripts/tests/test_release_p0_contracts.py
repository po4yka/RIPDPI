#!/usr/bin/env python3
"""P0 contracts for immutable release-candidate production and promotion."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

from scripts.ci.release_signature_fingerprint import extract_certificate_sha256


ROOT = Path(__file__).resolve().parents[2]


def workflow(name: str) -> str:
    return (ROOT / ".github/workflows" / name).read_text(encoding="utf-8")


def job(source: str, name: str) -> str:
    match = re.search(rf"(?ms)^  {re.escape(name)}:\n.*?(?=^  [\w-]+:\n|\Z)", source)
    if match is None:
        raise AssertionError(f"missing job {name}")
    return match.group(0)


class ReleaseP0ContractsTest(unittest.TestCase):
    def test_candidate_requires_exact_sha_main_ci_before_expensive_jobs(self) -> None:
        source = workflow("release-candidate.yml")
        preflight = job(source, "candidate-preflight")
        producer = job(source, "pluggable-transport-assets")
        signing = job(source, "build-signed-candidate")

        self.assertIn("actions: read", preflight)
        self.assertNotIn("environment: release-signing", preflight)
        self.assertIn('test "$GITHUB_REF" = refs/heads/main', preflight)
        self.assertIn("scripts/ci/require_successful_ci_run.py", preflight)
        self.assertIn(
            '--contract quality/release-gates/release-contract.json', preflight
        )
        self.assertIn("needs: candidate-preflight", producer)
        self.assertIn("needs: candidate-preflight", job(source, "xray-native"))
        self.assertIn("needs: [pluggable-transport-assets, xray-native]", signing)

    def test_candidate_is_built_once_inside_signing_environment(self) -> None:
        source = workflow("release-candidate.yml")
        signing = job(source, "build-signed-candidate")
        producer = job(source, "pluggable-transport-assets")

        self.assertIn("needs: [pluggable-transport-assets, xray-native]", signing)
        self.assertIn(":core:engine:buildPluggableTransportAssets", producer)
        self.assertIn("-Pripdpi.pluggableTransportAssetsMode=source", producer)
        self.assertIn("-Pripdpi.pluggableTransportAssetsStrictFailures=true", producer)
        self.assertIn("manifest_sha256", producer)
        self.assertIn("environment: release-signing", producer)
        self.assertIn("Download verified pluggable transport assets", signing)
        self.assertIn("Extract verified pluggable transport assets", signing)
        self.assertIn("ripdpi.prebuiltPluggableTransportAssetsDir", signing)
        self.assertIn("ripdpi.prebuiltPluggableTransportAssetsManifestSha256", signing)
        self.assertIn("environment: release-signing", signing)
        self.assertIn(":app:assembleGithubFullReleaseAndroidTest", signing)
        self.assertIn("-Pripdpi.testBuildType=release", signing)

        build_step = re.search(
            r"(?ms)^      - name: Build signed release candidate once\n.*?(?=^      - name:|\Z)",
            signing,
        )
        self.assertIsNotNone(build_step)
        assert build_step is not None
        gradle_invocations = build_step.group(0).split("./gradlew ")[1:]
        self.assertEqual(2, len(gradle_invocations))
        bundle_invocation, apk_invocation = gradle_invocations
        self.assertIn(":app:bundlePlayFullRelease", bundle_invocation)
        self.assertIn("-Pripdpi.enableAbiSplits=false", bundle_invocation)
        self.assertNotIn(":app:assembleGithubFullRelease", bundle_invocation)
        self.assertNotIn(":app:bundlePlayFullRelease", apk_invocation)
        self.assertIn(":app:assembleFdroidFullRelease", apk_invocation)
        self.assertIn(":app:assembleGithubFullRelease", apk_invocation)
        self.assertIn(":app:assembleGithubFullReleaseAndroidTest", apk_invocation)
        self.assertIn("-Pripdpi.enableAbiSplits=true", apk_invocation)

        signing_key = signing.index("Materialize release signing key")
        signing_identity = signing.index("Verify release signing identity before build")
        native_build = signing.index("Build signed release candidate once")
        key_removal = signing.index("Remove release signing key")
        mapping_verification = signing.index(
            "python3 scripts/ci/verify_jni_readiness_mapping.py"
        )
        artifact_normalization = signing.index("Normalize channel artifacts and metadata")
        self.assertLess(signing_key, signing_identity)
        self.assertLess(signing_identity, native_build)
        self.assertLess(native_build, key_removal)
        self.assertLess(key_removal, mapping_verification)
        self.assertLess(mapping_verification, artifact_normalization)

        self.assertGreaterEqual(signing.count("release_signature_fingerprint.py"), 3)
        self.assertIn("APK signature verification failed", signing)
        self.assertIn("AAB signature verification failed", signing)
        self.assertIn("keytool -exportcert", signing)
        self.assertIn("keytool -importcert -noprompt", signing)
        self.assertIn("release-verification-truststore.p12", signing)
        self.assertIn("jarsigner -J-Duser.language=en -verify -strict", signing)
        self.assertIn('"$aab" ripdpi-release', signing)
        self.assertIn("Remove release verification certificate", signing)
        self.assertIn("release_candidate_manifest.py create", signing)
        self.assertIn("RIPDPI_RELEASE_CERT_SHA256", signing)
        self.assertNotIn("contents: write", signing)
        self.assertNotIn("id-token: write", signing)

    def test_candidate_manifest_keeps_test_apk_out_of_publish_set(self) -> None:
        source = workflow("release-candidate.yml")
        signing = job(source, "build-signed-candidate")
        self.assertIn("app-github-release-androidTest.apk", signing)
        self.assertIn("release_candidate_manifest.py create", signing)
        self.assertIn('name: android-release-candidate', signing)

        manifest_source = (
            ROOT / "scripts/ci/release_candidate_manifest.py"
        ).read_text(encoding="utf-8")
        self.assertIn('EVIDENCE_TEST = "app-github-release-androidTest.apk"', manifest_source)
        self.assertIn("PUBLISH_FILES = EXPECTED_FILES - {EVIDENCE_TEST}", manifest_source)

    def test_release_instrumentation_is_checked_before_signing_candidate(self) -> None:
        ci_source = workflow("ci.yml")
        candidate_source = workflow("release-candidate.yml")
        self.assertIn(":app:assembleGithubFullReleaseAndroidTest", ci_source)
        self.assertIn("-Pripdpi.testBuildType=release", ci_source)
        self.assertIn(":app:assembleGithubFullReleaseAndroidTest", candidate_source)
        self.assertIn("-Pripdpi.testBuildType=release", candidate_source)

    def test_release_signature_fingerprint_accepts_tool_output_variants(self) -> None:
        expected = "ab" * 32
        colon_delimited = ":".join(["AB"] * 32)

        self.assertEqual(
            expected,
            extract_certificate_sha256(
                f"Signer #1 certificate SHA-256 digest: {colon_delimited}\r\n",
            ),
        )
        self.assertEqual(
            expected,
            extract_certificate_sha256(f"  SHA256: {colon_delimited}  \n"),
        )
        self.assertEqual(
            expected,
            extract_certificate_sha256(
                "\x1b[32mSigner 1 certificate SHA256 digest: "
                f"{colon_delimited} (verified)\x1b[0m\n",
            ),
        )
        self.assertEqual(
            expected,
            extract_certificate_sha256(
                f"certificate SHA 256 digest: {colon_delimited.replace(':', ' ')}\n",
            ),
        )

    def test_release_signature_fingerprint_rejects_ambiguous_or_malformed_output(
        self,
    ) -> None:
        valid = "ab" * 32
        for output in (
            "",
            "SHA256: not-a-digest",
            valid,
            f"SHA-1: {valid}",
            f"SHA256: {valid} {'cd' * 32}",
            f"SHA256: {valid}\nSHA256: {valid}\n",
        ):
            with self.subTest(output=output):
                with self.assertRaises(ValueError):
                    extract_certificate_sha256(output)

    def test_release_only_promotes_exact_candidate(self) -> None:
        source = workflow("release.yml")
        prepare = job(source, "prepare-exact-candidate")
        publish = job(source, "publish")

        self.assertNotIn("assembleGithub", source)
        self.assertNotIn("KEYSTORE_", source)
        self.assertNotIn("./.github/actions/setup-android-rust", source)
        self.assertIn("RIPDPI_RELEASE_CANDIDATE_RUN_ID", prepare)
        self.assertIn('"conclusion": "success"', prepare)
        self.assertIn('"event": "workflow_dispatch"', prepare)
        self.assertIn('"head_sha": os.environ["GITHUB_SHA"]', prepare)
        self.assertIn('"path": ".github/workflows/release-candidate.yml"', prepare)
        self.assertIn("run-id: ${{ env.CANDIDATE_RUN_ID }}", prepare)
        self.assertIn('name: android-release-candidate', prepare)
        self.assertIn("--expected-source-sha \"$GITHUB_SHA\"", prepare)
        self.assertIn("environment: release-publish", publish)
        self.assertIn("overwrite_files: false", publish)
        self.assertIn("draft: true", publish)
        self.assertEqual(1, source.count("contents: write"))
        self.assertNotIn("id-token: write", publish)


if __name__ == "__main__":
    unittest.main()
