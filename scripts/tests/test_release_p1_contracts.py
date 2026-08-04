#!/usr/bin/env python3

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci import release_candidate_manifest as candidate
from scripts.ci.release_dossier import DossierError, create_dossier
from scripts.ci.verify_release_draft import DraftError, verify
from scripts.ci.verify_remote_release_tag import TagError, peel_tag


class ReleaseP1ContractsTest(unittest.TestCase):
    def test_peels_annotated_remote_tag_to_exact_commit(self) -> None:
        objects = {
            "git/ref/tags/v0.1.4": {"object": {"type": "tag", "sha": "b" * 40}},
            f"git/tags/{'b' * 40}": {"object": {"type": "commit", "sha": "a" * 40}},
        }
        self.assertEqual("a" * 40, peel_tag("v0.1.4", "a" * 40, objects.__getitem__))
        with self.assertRaisesRegex(TagError, "expected"):
            peel_tag("v0.1.4", "c" * 40, objects.__getitem__)

    def test_only_exact_draft_assets_can_resume(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            expected = root / "expected"
            actual = root / "actual"
            expected.mkdir()
            actual.mkdir()
            (expected / "app.apk").write_bytes(b"exact")
            (actual / "app.apk").write_bytes(b"exact")
            notes = root / "notes.md"
            notes.write_text("notes\n", encoding="utf-8")
            metadata = {"tagName": "v0.1.4", "isDraft": True, "body": "notes\n"}
            verify(metadata, expected, actual, notes, "v0.1.4")
            (actual / "app.apk").write_bytes(b"changed")
            with self.assertRaisesRegex(DraftError, "changed"):
                verify(metadata, expected, actual, notes, "v0.1.4")
            with self.assertRaisesRegex(DraftError, "not the exact"):
                verify(
                    {**metadata, "isDraft": False}, expected, expected, notes, "v0.1.4"
                )

    def test_dossier_binds_candidate_notes_attestation_and_publish_inventory(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            candidate_dir = root / "candidate"
            publish = root / "publish"
            candidate_dir.mkdir()
            publish.mkdir()
            for name in candidate.EXPECTED_FILES:
                (candidate_dir / name).write_bytes(name.encode())
            manifest = candidate.create_manifest(
                candidate_dir,
                "a" * 40,
                release_tag="v0.1.4",
                version_name="0.1.4",
                version_code=12,
                signer_certificate_sha256="b" * 64,
                pluggable_transport_manifest_sha256="c" * 64,
                candidate_run_id=111,
            )
            manifest_path = candidate_dir / "candidate-manifest.json"
            manifest_path.write_text(
                json.dumps(manifest, sort_keys=True) + "\n", encoding="utf-8"
            )
            (publish / "candidate-manifest.json").write_bytes(
                manifest_path.read_bytes()
            )
            notes = publish / "release-notes.md"
            notes.write_text("Approved notes\n", encoding="utf-8")
            (publish / "app.apk").write_bytes(b"apk")
            receipt = publish / "candidate-attestation.json"
            receipt.write_text(
                json.dumps(
                    {
                        "attestationId": "12345",
                        "attestationUrl": "https://github.com/po4yka/RIPDPI/attestations/12345",
                        "candidateRunId": 111,
                        "sourceSha": "a" * 40,
                        "version": "ripdpi_candidate_attestation_v1",
                    }
                ),
                encoding="utf-8",
            )
            dossier = create_dossier(
                publish,
                manifest_path,
                receipt,
                notes,
                publish_run_id=222,
                assurance_profile="owner-accepted",
                waivers=["physical-device-gap", "physical-device-gap"],
            )
            self.assertEqual(111, dossier["candidateRunId"])
            self.assertEqual(222, dossier["publishRunId"])
            self.assertEqual("12345", dossier["attestation"]["id"])
            self.assertEqual(["physical-device-gap"], dossier["waivers"])
            self.assertEqual(
                {
                    "app.apk",
                    "candidate-manifest.json",
                    "candidate-attestation.json",
                    "release-notes.md",
                },
                set(dossier["artifacts"]),
            )
            self.assertEqual(64, len(dossier["artifactInventorySha256"]))

            receipt.write_text(
                receipt.read_text().replace(
                    '"candidateRunId": 111', '"candidateRunId": 999'
                )
            )
            with self.assertRaisesRegex(DossierError, "identity differs"):
                create_dossier(
                    publish,
                    manifest_path,
                    receipt,
                    notes,
                    publish_run_id=222,
                    assurance_profile="artifact-publish",
                    waivers=[],
                )

    def test_workflows_bind_notes_tag_dossier_and_attestation_before_publish(
        self,
    ) -> None:
        root = Path(__file__).resolve().parents[2]
        candidate_workflow = (
            root / ".github/workflows/release-candidate.yml"
        ).read_text(encoding="utf-8")
        release_workflow = (root / ".github/workflows/release.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("release-candidate-attestation", candidate_workflow)
        self.assertIn("steps.attest.outputs.attestation-id", candidate_workflow)
        self.assertIn('--candidate-run-id "$GITHUB_RUN_ID"', candidate_workflow)
        self.assertGreaterEqual(
            release_workflow.count("verify_remote_release_tag.py"), 3
        )
        self.assertIn("releases/generate-notes", release_workflow)
        self.assertIn("release_dossier.py", release_workflow)
        self.assertIn("verify_release_draft.py", release_workflow)
        self.assertIn("body_path: publish/release-notes.md", release_workflow)
        self.assertNotIn("generate_release_notes: true", release_workflow)
        self.assertLess(
            release_workflow.index("releases/generate-notes"),
            release_workflow.index("environment: release-publish"),
        )


if __name__ == "__main__":
    unittest.main()
