#!/usr/bin/env python3
"""Create the self-contained manifest published with every GitHub release."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

try:
    from scripts.ci.release_candidate_manifest import validate_manifest_document
except ModuleNotFoundError:  # Direct execution from scripts/ci.
    from release_candidate_manifest import validate_manifest_document


VERSION = "ripdpi_release_dossier_v1"


class DossierError(ValueError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def create_dossier(
    publish_dir: Path,
    candidate_manifest_path: Path,
    attestation_receipt_path: Path,
    notes_path: Path,
    publish_run_id: int,
    assurance_profile: str,
    waivers: list[str],
) -> dict:
    candidate = json.loads(candidate_manifest_path.read_text(encoding="utf-8"))
    validate_manifest_document(
        candidate, expected_source_sha=candidate.get("sourceSha", "")
    )
    attestation = json.loads(attestation_receipt_path.read_text(encoding="utf-8"))
    expected_attestation_keys = {
        "attestationId",
        "attestationUrl",
        "candidateRunId",
        "sourceSha",
        "version",
    }
    if set(attestation) != expected_attestation_keys:
        raise DossierError("candidate attestation receipt keys differ")
    if (
        attestation["version"] != "ripdpi_candidate_attestation_v1"
        or attestation["candidateRunId"] != candidate["candidateRunId"]
        or attestation["sourceSha"] != candidate["sourceSha"]
        or not str(attestation["attestationId"]).strip()
        or not str(attestation["attestationUrl"]).startswith("https://github.com/")
    ):
        raise DossierError("candidate attestation receipt identity differs")
    if isinstance(publish_run_id, bool) or publish_run_id <= 0:
        raise DossierError("publish run id must be positive")
    if not notes_path.is_file() or not notes_path.read_text(encoding="utf-8").strip():
        raise DossierError("release notes must be non-empty")
    if assurance_profile not in {
        "artifact-publish",
        "device-qualified",
        "owner-accepted",
    }:
        raise DossierError("release assurance profile is unknown")

    artifacts = {}
    for path in sorted(publish_dir.iterdir()):
        if path.name in {"release-manifest.json", "SHA256SUMS"}:
            continue
        if path.is_symlink() or not path.is_file():
            raise DossierError(f"publish entry must be a regular file: {path}")
        artifacts[path.name] = {
            "sha256": sha256(path),
            "sizeBytes": path.stat().st_size,
        }
    if not artifacts:
        raise DossierError("release artifact inventory is empty")
    required_records = {
        "candidate-manifest.json",
        "candidate-attestation.json",
        "release-notes.md",
    }
    if not required_records.issubset(artifacts):
        raise DossierError("release artifact inventory omits provenance records")
    inventory_digest = hashlib.sha256(
        (json.dumps(artifacts, sort_keys=True, separators=(",", ":")) + "\n").encode()
    ).hexdigest()
    return {
        "artifacts": artifacts,
        "artifactInventorySha256": inventory_digest,
        "assuranceProfile": assurance_profile,
        "attestation": {
            "id": str(attestation["attestationId"]),
            "url": attestation["attestationUrl"],
        },
        "candidateManifestSha256": sha256(candidate_manifest_path),
        "candidateRunId": candidate["candidateRunId"],
        "pluggableTransportManifestSha256": candidate[
            "pluggableTransportManifestSha256"
        ],
        "publishRunId": publish_run_id,
        "releaseNotesSha256": sha256(notes_path),
        "releaseTag": candidate["releaseTag"],
        "signerCertificateSha256": candidate["signerCertificateSha256"],
        "sourceSha": candidate["sourceSha"],
        "version": VERSION,
        "versionCode": candidate["versionCode"],
        "versionName": candidate["versionName"],
        "waivers": sorted(set(waivers)),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--publish-directory", required=True, type=Path)
    parser.add_argument("--candidate-manifest", required=True, type=Path)
    parser.add_argument("--attestation-receipt", required=True, type=Path)
    parser.add_argument("--release-notes", required=True, type=Path)
    parser.add_argument("--publish-run-id", required=True, type=int)
    parser.add_argument("--assurance-profile", required=True)
    parser.add_argument("--waiver", action="append", default=[])
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        dossier = create_dossier(
            args.publish_directory,
            args.candidate_manifest,
            args.attestation_receipt,
            args.release_notes,
            args.publish_run_id,
            args.assurance_profile,
            args.waiver,
        )
        args.output.write_text(
            json.dumps(dossier, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
    except (OSError, json.JSONDecodeError, DossierError, ValueError) as error:
        print(f"release dossier creation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
