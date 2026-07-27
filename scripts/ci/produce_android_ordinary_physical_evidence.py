#!/usr/bin/env python3
"""Materialize a private ordinary bundle from one attested physical Android run."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


CI_DIR = Path(__file__).resolve().parent
if str(CI_DIR) not in sys.path:
    sys.path.insert(0, str(CI_DIR))

import android_ordinary_physical_attestation as physical_attestation  # noqa: E402
import android_ordinary_raw_evidence as raw_evidence  # noqa: E402
import android_ordinary_semantic_oracles as oracles  # noqa: E402


ROOT = Path(__file__).resolve().parents[2]
OBSERVATION_VERSION = "android_ordinary_physical_observations_v1"
SHA1_RE = re.compile(r"[0-9a-f]{40}\Z")
SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
OBSERVATION_KEYS = {
    "actions",
    "apiLevel",
    "appApkSha256",
    "deviceCodename",
    "deviceManufacturer",
    "hardwareAttestation",
    "kernelRelease",
    "runId",
    "sourceSha",
    "testApkSha256",
    "version",
}
ACTION_KEYS = {
    "actionId",
    "correlationId",
    "dnsObservation",
    "event",
    "fixture",
    "probes",
    "routePhases",
    "windowFinishedAtEpochMs",
    "windowStartedAtEpochMs",
}


class ProducerError(ValueError):
    pass


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _strict_json(path: Path, *, maximum: int = 512 * 1024) -> dict[str, Any]:
    metadata = path.stat(follow_symlinks=False)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_nlink != 1
        or not 0 < metadata.st_size <= maximum
    ):
        raise ProducerError(f"{path.name} must be a bounded single-link regular file")
    raw = path.read_bytes()
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProducerError(f"{path.name} is not valid JSON") from error
    if not isinstance(value, dict):
        raise ProducerError(f"{path.name} must contain an object")
    return value


def current_clean_source_sha() -> str:
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=ROOT,
        capture_output=True,
        check=False,
        text=True,
    )
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        capture_output=True,
        check=False,
        text=True,
    )
    sha = revision.stdout.strip()
    if (
        status.returncode != 0
        or status.stdout
        or revision.returncode != 0
        or SHA1_RE.fullmatch(sha) is None
    ):
        raise ProducerError("physical evidence requires an exact clean source checkout")
    return sha


def validate_observations(
    observations: dict[str, Any],
    *,
    source_sha: str,
    app_sha256: str,
    test_sha256: str,
) -> None:
    if set(observations) != OBSERVATION_KEYS:
        raise ProducerError(
            "physical observation inventory differs from the source-owned schema"
        )
    if observations["version"] != OBSERVATION_VERSION:
        raise ProducerError("physical observation version is unsupported")
    expected = {
        "sourceSha": source_sha,
        "appApkSha256": app_sha256,
        "testApkSha256": test_sha256,
    }
    for key, value in expected.items():
        if observations[key] != value:
            raise ProducerError(f"physical observations have stale {key}")
    run_id = observations["runId"]
    if not isinstance(run_id, str) or SHA256_RE.fullmatch(run_id) is None:
        raise ProducerError("physical observation runId is malformed")
    actions = observations["actions"]
    if not isinstance(actions, list) or len(actions) != len(raw_evidence.ACTION_SPECS):
        raise ProducerError(
            "physical observations do not contain exactly seven actions"
        )
    for action, spec in zip(actions, raw_evidence.ACTION_SPECS, strict=True):
        if (
            not isinstance(action, dict)
            or set(action) != ACTION_KEYS
            or action["actionId"] != spec.action_id
        ):
            raise ProducerError("physical action inventory or order differs")
        expected_correlation = hashlib.sha256(
            f"{run_id}:{spec.action_id}".encode()
        ).hexdigest()
        if action["correlationId"] != expected_correlation:
            raise ProducerError(f"{spec.action_id} correlation is not run-derived")
        started = action["windowStartedAtEpochMs"]
        finished = action["windowFinishedAtEpochMs"]
        if (
            isinstance(started, bool)
            or isinstance(finished, bool)
            or not isinstance(started, int)
            or not isinstance(finished, int)
            or started <= 0
            or started >= finished
        ):
            raise ProducerError(f"{spec.action_id} observation window is invalid")
    challenge = physical_attestation.expected_challenge(
        source_sha, app_sha256, test_sha256, run_id
    )
    physical_attestation.verify_hardware_attestation(
        observations["hardwareAttestation"], challenge=challenge
    )


def expected_raw_documents(
    observations: dict[str, Any],
) -> dict[str, tuple[dict[str, Any], dict[str, Any]]]:
    source_sha = observations["sourceSha"]
    app_sha = observations["appApkSha256"]
    test_sha = observations["testApkSha256"]
    documents: dict[str, tuple[dict[str, Any], dict[str, Any]]] = {}
    for action in observations["actions"]:
        common = {
            "actionId": action["actionId"],
            "correlationId": action["correlationId"],
            "sourceSha": source_sha,
            "windowFinishedAtEpochMs": action["windowFinishedAtEpochMs"],
            "windowStartedAtEpochMs": action["windowStartedAtEpochMs"],
        }
        receipt = {
            **common,
            "appApkSha256": app_sha,
            "dnsObservation": action["dnsObservation"],
            "event": action["event"],
            "fixture": action["fixture"],
            "probes": action["probes"],
            "testApkSha256": test_sha,
            "version": oracles.RECEIPT_VERSION,
        }
        route = {
            **common,
            "phases": action["routePhases"],
            "version": oracles.ROUTE_SNAPSHOT_VERSION,
        }
        documents[action["actionId"]] = (receipt, route)
    return documents


def _private_write(path: Path, payload: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW
    descriptor = os.open(path, flags, 0o600)
    try:
        remaining = memoryview(payload)
        while remaining:
            written = os.write(descriptor, remaining)
            if written <= 0:
                raise OSError("private evidence write made no progress")
            remaining = remaining[written:]
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def produce(args: argparse.Namespace) -> None:
    if (
        not args.artifact_root.is_absolute()
        or not args.manifest.is_absolute()
        or not args.attestation.is_absolute()
    ):
        raise ProducerError("all output paths must be absolute")
    source_sha = current_clean_source_sha()
    if args.source_sha != source_sha:
        raise ProducerError("requested source SHA is not the clean checkout HEAD")
    app_sha = sha256_file(args.app_apk)
    test_sha = sha256_file(args.test_apk)
    if app_sha == test_sha:
        raise ProducerError("app and test APKs must be distinct")
    observations = _strict_json(args.observations)
    validate_observations(
        observations, source_sha=source_sha, app_sha256=app_sha, test_sha256=test_sha
    )
    capture_metadata = args.capture.stat(follow_symlinks=False)
    if (
        not stat.S_ISREG(capture_metadata.st_mode)
        or capture_metadata.st_nlink != 1
        or not 24 <= capture_metadata.st_size <= oracles.MAX_PCAP_BYTES
    ):
        raise ProducerError("capture must be one bounded classic PCAP regular file")
    oracles.parse_classic_pcap(args.capture.read_bytes())
    transcript = args.instrumentation_transcript.read_bytes()
    try:
        transcript_text = transcript.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ProducerError("instrumentation transcript is not UTF-8") from error
    if (
        not transcript
        or len(transcript) > 4 * 1024 * 1024
        or transcript_text.splitlines().count("OK (1 test)") != 1
        or any(
            sentinel in transcript_text
            for sentinel in ("FAILURES", "INSTRUMENTATION_FAILED", "AssumptionViolated")
        )
    ):
        raise ProducerError(
            "instrumentation transcript does not prove the exact single test"
        )
    if (
        args.artifact_root.exists()
        or args.manifest.exists()
        or args.attestation.exists()
    ):
        raise ProducerError("physical evidence outputs must not already exist")
    args.artifact_root.mkdir(mode=0o700, parents=True)
    args.artifact_root.chmod(0o700)
    documents = expected_raw_documents(observations)
    capture_payload = args.capture.read_bytes()
    capture_sha = hashlib.sha256(capture_payload).hexdigest()
    manifest_actions = []
    try:
        for spec in raw_evidence.ACTION_SPECS:
            action = next(
                item
                for item in observations["actions"]
                if item["actionId"] == spec.action_id
            )
            receipt, route = documents[spec.action_id]
            payloads = {
                "action-receipt": canonical_json_bytes(receipt),
                "packet-capture": capture_payload,
                "route-snapshot": canonical_json_bytes(route),
            }
            artifact_entries = []
            for kind in raw_evidence.ARTIFACT_KINDS:
                suffix = "pcap" if kind == "packet-capture" else "json"
                name = f"{spec.action_id}.{kind}.{suffix}"
                payload = payloads[kind]
                _private_write(args.artifact_root / name, payload)
                artifact_entries.append(
                    {
                        "kind": kind,
                        "path": name,
                        "sha256": hashlib.sha256(payload).hexdigest(),
                        "sizeBytes": len(payload),
                        "vantage": raw_evidence.ARTIFACT_VANTAGES[kind],
                        "windowFinishedAtEpochMs": action["windowFinishedAtEpochMs"],
                        "windowStartedAtEpochMs": action["windowStartedAtEpochMs"],
                    }
                )
            manifest_actions.append(
                {
                    "actionId": spec.action_id,
                    "artifacts": artifact_entries,
                    "correlationId": action["correlationId"],
                    "gateIds": list(spec.gate_ids),
                    "windowFinishedAtEpochMs": action["windowFinishedAtEpochMs"],
                    "windowStartedAtEpochMs": action["windowStartedAtEpochMs"],
                }
            )
        manifest = {
            "actions": manifest_actions,
            "appApkSha256": app_sha,
            "artifactRoot": str(args.artifact_root),
            "createdAtEpochMs": int(time.time() * 1000),
            "runId": observations["runId"],
            "sourceSha": source_sha,
            "testApkSha256": test_sha,
            "version": raw_evidence.BUNDLE_VERSION,
        }
        manifest_payload = canonical_json_bytes(manifest)
        _private_write(args.manifest, manifest_payload)
        attestation = {
            "appApkSha256": app_sha,
            "captureSha256": capture_sha,
            "device": {
                "apiLevel": observations["apiLevel"],
                "codename": observations["deviceCodename"],
                "kernelRelease": observations["kernelRelease"],
                "manufacturer": observations["deviceManufacturer"],
                "serialSha256": hashlib.sha256(args.device_serial.encode()).hexdigest(),
            },
            "hardwareAttestation": observations["hardwareAttestation"],
            "instrumentationTranscriptSha256": hashlib.sha256(transcript).hexdigest(),
            "manifestSha256": hashlib.sha256(manifest_payload).hexdigest(),
            "observations": observations,
            "producerSha256": sha256_file(Path(__file__).resolve()),
            "runId": observations["runId"],
            "sourceSha": source_sha,
            "testApkSha256": test_sha,
            "version": physical_attestation.ATTESTATION_VERSION,
        }
        _private_write(args.attestation, canonical_json_bytes(attestation))
    except Exception:
        shutil.rmtree(args.artifact_root, ignore_errors=True)
        for path in (args.manifest, args.attestation):
            path.unlink(missing_ok=True)
        raise


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--observations", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--instrumentation-transcript", type=Path, required=True)
    parser.add_argument("--app-apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--device-serial", required=True)
    parser.add_argument("--artifact-root", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--attestation", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        produce(args)
    except (
        OSError,
        ProducerError,
        physical_attestation.AttestationError,
        oracles.OracleError,
    ) as error:
        print(
            f"Android ordinary physical producer failed closed: {error}",
            file=sys.stderr,
        )
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
