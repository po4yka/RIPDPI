from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.ci import release_candidate_manifest as candidate


class ReleaseCandidateManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        for name in candidate.EXPECTED_FILES:
            (self.directory / name).write_bytes((name + "\n").encode())
        self.source_sha = "a" * 40

    def create_manifest(self) -> dict:
        return candidate.create_manifest(
            self.directory,
            self.source_sha,
            release_tag="v0.1.4",
            version_name="0.1.4",
            version_code=12,
            signer_certificate_sha256="b" * 64,
            pluggable_transport_manifest_sha256="c" * 64,
            candidate_run_id=1234,
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_exact_inventory_round_trips(self) -> None:
        manifest = self.create_manifest()
        validated = candidate.validate_manifest(
            manifest,
            self.directory,
            expected_source_sha=self.source_sha,
            expected_client_sha256=manifest["artifacts"][candidate.EVIDENCE_CLIENT][
                "sha256"
            ],
        )
        self.assertEqual(candidate.VERSION, validated["version"])
        self.assertFalse(validated["artifacts"][candidate.EVIDENCE_TEST]["publish"])
        self.assertTrue(validated["artifacts"][candidate.EVIDENCE_CLIENT]["publish"])

    def test_tampered_candidate_is_rejected(self) -> None:
        manifest = self.create_manifest()
        (self.directory / candidate.EVIDENCE_CLIENT).write_bytes(b"tampered\n")
        with self.assertRaisesRegex(candidate.CandidateError, "metadata differs"):
            candidate.validate_manifest(
                manifest,
                self.directory,
                expected_source_sha=self.source_sha,
                expected_client_sha256=None,
            )

    def test_evidence_digest_mismatch_is_rejected(self) -> None:
        manifest = self.create_manifest()
        with self.assertRaisesRegex(candidate.CandidateError, "evidence client digest"):
            candidate.validate_manifest(
                manifest,
                self.directory,
                expected_source_sha=self.source_sha,
                expected_client_sha256="f" * 64,
            )

    def test_extra_file_is_rejected(self) -> None:
        (self.directory / "unexpected.apk").write_bytes(b"extra")
        with self.assertRaisesRegex(candidate.CandidateError, "inventory differs"):
            self.create_manifest()

    def test_release_identity_mismatch_is_rejected(self) -> None:
        with self.assertRaisesRegex(candidate.CandidateError, "tag and version"):
            candidate.create_manifest(
                self.directory,
                self.source_sha,
                release_tag="v0.1.5",
                version_name="0.1.4",
                version_code=12,
                signer_certificate_sha256="b" * 64,
                pluggable_transport_manifest_sha256="c" * 64,
                candidate_run_id=1234,
            )


if __name__ == "__main__":
    unittest.main()
