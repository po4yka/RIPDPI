#!/usr/bin/env python3
"""Create and verify the immutable Android release-candidate inventory."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


VERSION = "ripdpi_release_candidate_v1"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
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


def create_manifest(directory: Path, source_sha: str) -> dict:
    if SHA1_RE.fullmatch(source_sha) is None:
        raise CandidateError("source SHA is malformed")
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
        "sourceSha": source_sha,
        "version": VERSION,
    }


def validate_manifest(
    manifest: dict,
    directory: Path,
    *,
    expected_source_sha: str,
    expected_client_sha256: str | None,
) -> dict:
    if set(manifest) != {
        "artifacts",
        "evidenceClient",
        "evidenceTest",
        "sourceSha",
        "version",
    }:
        raise CandidateError("candidate manifest keys differ")
    if manifest["version"] != VERSION:
        raise CandidateError("candidate manifest version differs")
    if manifest["sourceSha"] != expected_source_sha or SHA1_RE.fullmatch(expected_source_sha) is None:
        raise CandidateError("candidate source SHA differs")
    if manifest["evidenceClient"] != EVIDENCE_CLIENT or manifest["evidenceTest"] != EVIDENCE_TEST:
        raise CandidateError("candidate evidence artifact selection differs")

    files = candidate_files(directory)
    artifacts = manifest["artifacts"]
    if not isinstance(artifacts, dict) or set(artifacts) != EXPECTED_FILES:
        raise CandidateError("candidate manifest artifact inventory differs")
    for name, path in files.items():
        record = artifacts[name]
        if set(record) != {"publish", "sha256", "sizeBytes"}:
            raise CandidateError(f"candidate artifact record differs: {name}")
        digest = sha256(path)
        if (
            record["publish"] is not (name in PUBLISH_FILES)
            or not isinstance(record["sizeBytes"], int)
            or record["sizeBytes"] != path.stat().st_size
            or SHA256_RE.fullmatch(record["sha256"]) is None
            or record["sha256"] != digest
        ):
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
    verify = subparsers.add_parser("verify")
    verify.add_argument("--directory", type=Path, required=True)
    verify.add_argument("--manifest", type=Path, required=True)
    verify.add_argument("--expected-source-sha", required=True)
    verify.add_argument("--expected-client-sha256")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.command == "create":
            manifest = create_manifest(args.directory, args.source_sha)
            args.output.write_bytes(canonical_bytes(manifest))
        else:
            manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
            validate_manifest(
                manifest,
                args.directory,
                expected_source_sha=args.expected_source_sha,
                expected_client_sha256=args.expected_client_sha256,
            )
    except (CandidateError, json.JSONDecodeError, OSError) as error:
        print(f"release candidate validation failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
