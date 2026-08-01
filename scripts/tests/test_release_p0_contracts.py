from __future__ import annotations

import json
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
    def test_candidate_is_built_once_inside_signing_environment(self) -> None:
        source = workflow("release-candidate.yml")
        signing = job(source, "build-signed-candidate")
        producer = job(source, "pluggable-transport-assets")
        self.assertIn("needs: pluggable-transport-assets", signing)
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
        self.assertIn("-Pripdpi.enableAbiSplits=true", apk_invocation)
        compile_preflight = signing.index(":app:compileGithubFullReleaseAndroidTestKotlin")
        signing_key = signing.index("Materialize release signing key")
        signing_identity = signing.index("Verify release signing identity before build")
        native_build = signing.index("Build signed release candidate once")
        self.assertLess(compile_preflight, signing_key)
        self.assertLess(signing_key, signing_identity)
        self.assertLess(signing_identity, native_build)
        self.assertGreaterEqual(signing.count("release_signature_fingerprint.py"), 3)
        self.assertIn("APK signature verification failed", signing)
        self.assertIn("AAB signature verification failed", signing)
        self.assertIn('keytool -exportcert \\\n', signing)
        self.assertIn('keytool -importcert -noprompt \\\n', signing)
        self.assertIn('release-verification-truststore.p12', signing)
        self.assertIn('jarsigner -J-Duser.language=en -verify -strict', signing)
        self.assertIn('"$aab" ripdpi-release', signing)
        self.assertIn("Remove release verification certificate", signing)
        self.assertNotIn('jarsigner -verify -strict "$aab"', signing)
        self.assertIn("release_candidate_manifest.py create", signing)
        self.assertIn("RIPDPI_RELEASE_CERT_SHA256", signing)
        self.assertIn("Remove release signing key", signing)
        public_truststore = signing.index("release-verification-truststore.p12")
        private_key_removal = signing.index("Remove release signing key")
        strict_aab_verification = signing.index("jarsigner -J-Duser.language=en")
        public_cert_removal = signing.index("Remove release verification certificate")
        self.assertLess(public_truststore, private_key_removal)
        self.assertLess(private_key_removal, strict_aab_verification)
        self.assertLess(strict_aab_verification, public_cert_removal)
        self.assertNotIn("contents: write", signing)
        self.assertNotIn("id-token: write", signing)

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

    def test_evidence_installs_exact_candidate_and_does_not_rebuild(self) -> None:
        source = workflow("dns-ipv6-killswitch-evidence.yml")
        self.assertNotIn("./gradlew", source)
        for setup_input in (
            'setup-rust: "false"',
            'setup-gradle: "false"',
            'setup-sccache: "false"',
            'setup-android-ndk: "false"',
        ):
            self.assertIn(setup_input, source)
        self.assertNotIn("schedule:", source)
        self.assertIn("android-release-candidate", source)
        self.assertIn("app-github-release-x86_64.apk", source)
        self.assertIn("app-github-release-androidTest.apk", source)
        self.assertIn("release-candidate-run.json", source)
        self.assertNotRegex(source, r"(?m)^    env:\n(?:.*\n)*?      ORDINARY_RESULTS_BASE64:")

    def test_release_only_promotes_candidate_bound_to_evidence(self) -> None:
        source = workflow("release.yml")
        prepare = job(source, "prepare-exact-candidate")
        publish = job(source, "publish")
        self.assertNotIn("assembleGithub", source)
        self.assertNotIn("KEYSTORE_", source)
        self.assertNotIn("./.github/actions/setup-android-rust", source)
        self.assertIn("expected-client-sha256", prepare)
        self.assertIn("cmp -s", prepare)
        self.assertIn("environment: release-publish", publish)
        self.assertIn("overwrite_files: false", publish)
        self.assertIn("draft: true", publish)
        self.assertEqual(1, source.count("contents: write"))
        self.assertNotIn("id-token: write", publish)

    def test_physical_release_evidence_has_no_relaxations(self) -> None:
        policy = json.loads(
            (ROOT / "quality/release-gates/dns-ipv6-killswitch-gates.json").read_text(encoding="utf-8")
        )
        self.assertEqual([], policy["relaxedEvidenceRequirements"]["requirements"])

    def test_physical_producer_accepts_prebuilt_candidate_and_checker_binds_it(self) -> None:
        producer = (
            ROOT / "scripts/ci/run-android-ordinary-physical-evidence.sh"
        ).read_text(encoding="utf-8")
        checker = (
            ROOT / "scripts/ci/check_dns_ipv6_killswitch_gates.py"
        ).read_text(encoding="utf-8")
        self.assertIn("--app-apk", producer)
        self.assertIn("--test-apk", producer)
        self.assertIn("validate_release_artifact_bindings", checker)
        self.assertIn("physical_client_digests", checker)
        self.assertIn("publishable GitHub candidate APK", checker)


if __name__ == "__main__":
    unittest.main()
