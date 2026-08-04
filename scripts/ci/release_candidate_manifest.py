#!/usr/bin/env python3
"""Create and verify the immutable Android release-candidate inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


VERSION = "ripdpi_release_candidate_v2"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
SEMVER_RE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+\Z")
ABIS = ("arm64-v8a", "armeabi-v7a", "universal", "x86", "x86_64")
EVIDENCE_CLIENT = "app-github-release-x86_64.apk"
EVIDENCE_TEST = "app-github-release-androidTest.apk"
EXPECTED_FILES = {
    "app-play-full-release.aab",
    EVIDENCE_TEST,
    "fdroid-apk-size-report.md",
    "github-apk-size-report.md",
    "update.json",
} | {
    f"app-{channel}-release-{abi}.apk"
    for channel in ("fdroid", "github")
    for abi in ABIS
}
PUBLISH_FILES = EXPECTED_FILES - {EVIDENCE_TEST}


class CandidateError(ValueError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def candidate_files(directory: Path) -> dict[str, Path]:
    if not directory.is_dir():
        raise CandidateError(f"candidate directory does not exist: {directory}")
    paths = {}
    for path in directory.iterdir():
        if path.name == "candidate-manifest.json":
            continue
        if path.is_symlink() or not path.is_file():
            raise CandidateError(f"candidate entry must be a regular file: {path}")
        paths[path.name] = path
    if set(paths) != EXPECTED_FILES:
        raise CandidateError(
            "candidate file inventory differs; "
            f"missing={sorted(EXPECTED_FILES - set(paths))}, "
            f"extra={sorted(set(paths) - EXPECTED_FILES)}"
        )
    return paths


def create_manifest(
    directory: Path,
    source_sha: str,
    *,
    release_tag: str,
    version_name: str,
    version_code: int,
    signer_certificate_sha256: str,
    pluggable_transport_manifest_sha256: str,
    candidate_run_id: int,
) -> dict:
    if SHA1_RE.fullmatch(source_sha) is None:
        raise CandidateError("source SHA is malformed")
    if SEMVER_RE.fullmatch(version_name) is None or release_tag != f"v{version_name}":
        raise CandidateError("candidate release tag and version name differ")
    if isinstance(version_code, bool) or version_code <= 0:
        raise CandidateError("candidate version code must be positive")
    if isinstance(candidate_run_id, bool) or candidate_run_id <= 0:
        raise CandidateError("candidate run id must be positive")
    for name, digest in (
        ("signer certificate", signer_certificate_sha256),
        ("pluggable transport manifest", pluggable_transport_manifest_sha256),
    ):
        if SHA256_RE.fullmatch(digest) is None:
            raise CandidateError(f"candidate {name} digest is malformed")
    files = candidate_files(directory)
    return {
        "artifacts": {
            name: {
                "publish": name in PUBLISH_FILES,
                "sha256": sha256(path),
                "sizeBytes": path.stat().st_size,
            }
            for name, path in sorted(files.items())
        },
        "evidenceClient": EVIDENCE_CLIENT,
        "evidenceTest": EVIDENCE_TEST,
        "candidateRunId": candidate_run_id,
        "pluggableTransportManifestSha256": pluggable_transport_manifest_sha256,
        "releaseTag": release_tag,
        "signerCertificateSha256": signer_certificate_sha256,
        "sourceSha": source_sha,
        "version": VERSION,
        "versionCode": version_code,
        "versionName": version_name,
    }


def validate_manifest_document(
    manifest: dict,
    *,
    expected_source_sha: str,
    expected_release_tag: str | None = None,
    expected_candidate_run_id: int | None = None,
    expected_signer_certificate_sha256: str | None = None,
) -> dict:
    """Validate candidate metadata without requiring the artifact directory."""
    if set(manifest) != {
        "artifacts",
        "evidenceClient",
        "evidenceTest",
        "candidateRunId",
        "pluggableTransportManifestSha256",
        "releaseTag",
        "signerCertificateSha256",
        "sourceSha",
        "version",
        "versionCode",
        "versionName",
    }:
        raise CandidateError("candidate manifest keys differ")
    if manifest["version"] != VERSION:
        raise CandidateError("candidate manifest version differs")
    if (
        manifest["sourceSha"] != expected_source_sha
        or SHA1_RE.fullmatch(expected_source_sha) is None
    ):
        raise CandidateError("candidate source SHA differs")
    if (
        not isinstance(manifest["versionName"], str)
        or SEMVER_RE.fullmatch(manifest["versionName"]) is None
        or manifest["releaseTag"] != f"v{manifest['versionName']}"
    ):
        raise CandidateError("candidate release identity differs")
    if (
        expected_release_tag is not None
        and manifest["releaseTag"] != expected_release_tag
    ):
        raise CandidateError("candidate release tag differs")
    if (
        isinstance(manifest["versionCode"], bool)
        or not isinstance(manifest["versionCode"], int)
        or manifest["versionCode"] <= 0
    ):
        raise CandidateError("candidate version code differs")
    if (
        isinstance(manifest["candidateRunId"], bool)
        or not isinstance(manifest["candidateRunId"], int)
        or manifest["candidateRunId"] <= 0
    ):
        raise CandidateError("candidate run id differs")
    if (
        expected_candidate_run_id is not None
        and manifest["candidateRunId"] != expected_candidate_run_id
    ):
        raise CandidateError("candidate run id differs")
    for field in ("signerCertificateSha256", "pluggableTransportManifestSha256"):
        if (
            not isinstance(manifest[field], str)
            or SHA256_RE.fullmatch(manifest[field]) is None
        ):
            raise CandidateError(f"candidate {field} differs")
    if (
        expected_signer_certificate_sha256 is not None
        and manifest["signerCertificateSha256"] != expected_signer_certificate_sha256
    ):
        raise CandidateError("candidate signer certificate differs")
    if (
        manifest["evidenceClient"] != EVIDENCE_CLIENT
        or manifest["evidenceTest"] != EVIDENCE_TEST
    ):
        raise CandidateError("candidate evidence artifact selection differs")
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, dict) or set(artifacts) != EXPECTED_FILES:
        raise CandidateError("candidate manifest artifact inventory differs")
    for name, record in artifacts.items():
        if (
            not isinstance(record, dict)
            or set(record) != {"publish", "sha256", "sizeBytes"}
            or record["publish"] is not (name in PUBLISH_FILES)
            or isinstance(record["sizeBytes"], bool)
            or not isinstance(record["sizeBytes"], int)
            or record["sizeBytes"] <= 0
            or not isinstance(record["sha256"], str)
            or SHA256_RE.fullmatch(record["sha256"]) is None
        ):
            raise CandidateError(f"candidate artifact record differs: {name}")
    return manifest


def validate_manifest(
    manifest: dict,
    directory: Path,
    *,
    expected_source_sha: str,
    expected_client_sha256: str | None,
    expected_release_tag: str | None = None,
    expected_candidate_run_id: int | None = None,
    expected_signer_certificate_sha256: str | None = None,
) -> dict:
    validate_manifest_document(
        manifest,
        expected_source_sha=expected_source_sha,
        expected_release_tag=expected_release_tag,
        expected_candidate_run_id=expected_candidate_run_id,
        expected_signer_certificate_sha256=expected_signer_certificate_sha256,
    )
    files = candidate_files(directory)
    artifacts = manifest["artifacts"]
    for name, path in files.items():
        record = artifacts[name]
        digest = sha256(path)
        if record["sizeBytes"] != path.stat().st_size or record["sha256"] != digest:
            raise CandidateError(f"candidate artifact metadata differs: {name}")
    client_sha = artifacts[EVIDENCE_CLIENT]["sha256"]
    if expected_client_sha256 is not None and client_sha != expected_client_sha256:
        raise CandidateError("release candidate does not match evidence client digest")
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    create = subparsers.add_parser("create")
    create.add_argument("--directory", type=Path, required=True)
    create.add_argument("--source-sha", required=True)
    create.add_argument("--output", type=Path, required=True)
    create.add_argument("--release-tag", required=True)
    create.add_argument("--version-name", required=True)
    create.add_argument("--version-code", type=int, required=True)
    create.add_argument("--signer-certificate-sha256", required=True)
    create.add_argument("--pluggable-transport-manifest-sha256", required=True)
    create.add_argument("--candidate-run-id", type=int, required=True)
    verify = subparsers.add_parser("verify")
    verify.add_argument("--directory", type=Path, required=True)
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--expected-source-sha", required=True)
    verify.add_argument("--expected-client-sha256")
    verify.add_argument("--expected-release-tag")
    verify.add_argument("--expected-candidate-run-id", type=int)
    verify.add_argument("--expected-signer-certificate-sha256")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "create":
            manifest = create_manifest(
                args.directory,
                args.source_sha,
                release_tag=args.release_tag,
                version_name=args.version_name,
                version_code=args.version_code,
                signer_certificate_sha256=args.signer_certificate_sha256,
                pluggable_transport_manifest_sha256=args.pluggable_transport_manifest_sha256,
                candidate_run_id=args.candidate_run_id,
            )
            args.output.write_bytes(canonical_bytes(manifest))
        else:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
            validate_manifest(
                manifest,
                args.directory,
                expected_source_sha=args.expected_source_sha,
                expected_client_sha256=args.expected_client_sha256,
                expected_release_tag=args.expected_release_tag,
                expected_candidate_run_id=args.expected_candidate_run_id,
                expected_signer_certificate_sha256=args.expected_signer_certificate_sha256,
            )
    except (CandidateError, json.JSONDecodeError, OSError) as error:
        print(f"release candidate validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
