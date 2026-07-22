#!/usr/bin/env python3
"""Emit fail-closed local results for Android ordinary release gates.

There is intentionally no pluggable collector. The checked-in preflight binds
private raw packet/route artifacts to source and APK provenance, but PASS
remains impossible until source-owned semantic oracles interpret all seven
physical actions. This prevents hand-authored counters, summaries, or JUnit
state from becoming release evidence.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import stat
import subprocess
import sys
from pathlib import Path
from typing import Any


CI_DIR = Path(__file__).resolve().parent
if str(CI_DIR) not in sys.path:
    sys.path.insert(0, str(CI_DIR))

import android_ordinary_raw_evidence  # noqa: E402


ROOT = Path(__file__).resolve().parents[2]
RESULTS_VERSION = "dns_ipv6_killswitch_results_v1"
APPLIES_TO = "android-client-release"
SHA1_RE = re.compile(r"[0-9a-f]{40}")
# Reserved sentinel: no Git object can be trusted when HEAD lookup itself fails.
UNKNOWN_SOURCE_SHA = "0" * 40
SOURCE_OWNED_VERIFIER_AVAILABLE = False
UNAVAILABLE_CODE = "SOURCE_OWNED_VERIFIER_UNAVAILABLE"
UNAVAILABLE_REASON = (
    "source-owned semantic oracles are not implemented; ordinary PASS is forbidden"
)

ORDINARY_GATE_IDS = (
    "ipv4only-no-ipv6-dns-address-route",
    "ipv4only-no-direct-ipv6",
    "ipv4only-blocked-ipv6-only-connect",
    "ipv4only-empty-or-blocked-aaaa",
    "dualstack-default-route-through-tunnel",
    "dualstack-aaaa-through-tunnel",
    "killswitch-forced-disconnect",
    "killswitch-core-crash",
    "killswitch-wifi-lte-switch",
    "killswitch-sleep-wake",
    "killswitch-android-always-on-block",
)


class EvidenceError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class OutputDestination:
    def __init__(
        self, path: Path, parent_fd: int, parent_identity: tuple[int, ...]
    ) -> None:
        self.path = path
        self.parent_fd = parent_fd
        self.parent_identity = parent_identity

    def close(self) -> None:
        os.close(self.parent_fd)


def directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
    )


def open_output_destination(path: Path) -> OutputDestination:
    if not path.is_absolute():
        raise EvidenceError("OUTPUT_PATH_INVALID", "results output must be absolute")
    path.parent.mkdir(parents=True, exist_ok=True)
    resolved_parent = Path(os.path.realpath(path.parent))
    try:
        parent_fd = os.open(
            path.parent,
            os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_DIRECTORY,
        )
    except OSError as error:
        raise EvidenceError(
            "OUTPUT_PATH_INVALID", "results output parent must be a real directory"
        ) from error
    try:
        parent_metadata = os.fstat(parent_fd)
        resolved_metadata = resolved_parent.stat()
        if directory_identity(parent_metadata) != directory_identity(resolved_metadata):
            raise EvidenceError(
                "OUTPUT_PATH_INVALID", "results output parent changed while opened"
            )
        if parent_metadata.st_uid != os.getuid() or (
            stat.S_IMODE(parent_metadata.st_mode) & 0o077
        ):
            raise EvidenceError(
                "OUTPUT_PRIVACY_INVALID",
                "results output parent must be current-user-owned and private",
            )
        try:
            output_metadata = os.stat(
                path.name, dir_fd=parent_fd, follow_symlinks=False
            )
        except FileNotFoundError:
            output_metadata = None
        if output_metadata is not None and (
            not stat.S_ISREG(output_metadata.st_mode) or output_metadata.st_nlink != 1
        ):
            raise EvidenceError(
                "OUTPUT_PATH_INVALID",
                "existing results output must be a single-link regular file",
            )
        return OutputDestination(
            path=resolved_parent / path.name,
            parent_fd=parent_fd,
            parent_identity=directory_identity(parent_metadata),
        )
    except Exception:
        os.close(parent_fd)
        raise


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode()


def write_canonical_json(
    path: Path, value: Any, *, destination: OutputDestination | None = None
) -> None:
    owned_destination = destination is None
    target = open_output_destination(path) if destination is None else destination
    temporary = f".{path.name}.{secrets.token_hex(16)}"
    try:
        if directory_identity(os.fstat(target.parent_fd)) != target.parent_identity:
            raise EvidenceError(
                "OUTPUT_PATH_INVALID", "results output parent metadata changed"
            )
        descriptor = os.open(
            temporary,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC,
            0o600,
            dir_fd=target.parent_fd,
        )
        with os.fdopen(descriptor, "wb") as output:
            output.write(canonical_json_bytes(value))
            output.flush()
            os.fsync(output.fileno())
        if directory_identity(os.fstat(target.parent_fd)) != target.parent_identity:
            raise EvidenceError(
                "OUTPUT_PATH_INVALID", "results output parent metadata changed"
            )
        os.replace(
            temporary,
            path.name,
            src_dir_fd=target.parent_fd,
            dst_dir_fd=target.parent_fd,
        )
    finally:
        try:
            os.unlink(temporary, dir_fd=target.parent_fd)
        except FileNotFoundError:
            pass
        if owned_destination:
            target.close()


def reject_output_input_aliases(
    destination: OutputDestination, inputs: tuple[Path, ...]
) -> None:
    for source in inputs:
        source_path = Path(os.path.realpath(source))
        if destination.path == source_path:
            raise EvidenceError(
                "OUTPUT_ALIASES_INPUT", "results output aliases an evidence input"
            )
        try:
            output_metadata = os.stat(
                destination.path.name,
                dir_fd=destination.parent_fd,
                follow_symlinks=False,
            )
            source_metadata = source.stat()
            aliases = (output_metadata.st_dev, output_metadata.st_ino) == (
                source_metadata.st_dev,
                source_metadata.st_ino,
            )
        except OSError:
            aliases = False
        if aliases:
            raise EvidenceError(
                "OUTPUT_ALIASES_INPUT", "results output aliases an evidence input"
            )


def current_source_sha(root: Path = ROOT) -> str:
    status = subprocess.run(
        ["git", "status", "--porcelain", "--untracked-files=all"],
        cwd=root,
        capture_output=True,
        check=False,
        text=True,
    )
    if status.returncode != 0 or status.stdout:
        raise EvidenceError(
            "SOURCE_DIRTY", "ordinary evidence requires a clean source checkout"
        )
    return current_head_sha(root)


def current_head_sha(root: Path = ROOT) -> str:
    revision = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=root,
        capture_output=True,
        check=False,
        text=True,
    )
    source_sha = revision.stdout.strip()
    if revision.returncode != 0 or SHA1_RE.fullmatch(source_sha) is None:
        raise EvidenceError(
            "SOURCE_INVALID", "current source SHA could not be resolved"
        )
    return source_sha


def all_failure_results(
    source_sha: str,
    *,
    code: str = UNAVAILABLE_CODE,
    reason: str = UNAVAILABLE_REASON,
) -> dict[str, Any]:
    failure = {"reason": f"{code}: {reason}", "state": "FAIL"}
    return {
        "appliesTo": APPLIES_TO,
        "gateResults": {gate_id: dict(failure) for gate_id in ORDINARY_GATE_IDS},
        "sourceSha": source_sha,
        "version": RESULTS_VERSION,
    }


def semantic_failure_results(
    source_sha: str, provenance: dict[str, Any]
) -> dict[str, Any]:
    blockers = android_ordinary_raw_evidence.semantic_blockers_by_gate()
    if set(blockers) != set(ORDINARY_GATE_IDS):
        raise EvidenceError(
            "INVENTORY_MISMATCH", "semantic blocker inventory does not cover all gates"
        )
    return {
        "appliesTo": APPLIES_TO,
        "gateResults": {
            gate_id: {
                "reason": (
                    f"{blockers[gate_id]}: raw artifact provenance passed, but the "
                    "source-owned packet/route semantic oracle is not implemented"
                ),
                "state": "FAIL",
            }
            for gate_id in ORDINARY_GATE_IDS
        },
        "rawBundleProvenance": {
            "actionCount": provenance["actionCount"],
            "artifactCount": provenance["artifactCount"],
            "manifestSha256": provenance["manifestSha256"],
            "productionReady": False,
            "verifier": "android_ordinary_raw_evidence_v1",
        },
        "sourceSha": source_sha,
        "version": RESULTS_VERSION,
    }


def validate_pass_results(*_args: Any, **_kwargs: Any) -> None:
    raise EvidenceError(UNAVAILABLE_CODE, UNAVAILABLE_REASON)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--raw-manifest", type=Path)
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    args = parser.parse_args(argv)

    raw_arguments = (args.raw_manifest, args.app_apk, args.test_apk)
    if any(value is not None for value in raw_arguments) and not all(
        value is not None for value in raw_arguments
    ):
        parser.error(
            "--raw-manifest, --app-apk, and --test-apk must be supplied together"
        )

    try:
        destination = open_output_destination(args.output)
    except (OSError, EvidenceError) as error:
        print(
            f"Android ordinary producer refused unsafe output: {error}", file=sys.stderr
        )
        return 2

    try:
        reject_output_input_aliases(
            destination, tuple(value for value in raw_arguments if value is not None)
        )
        if args.raw_manifest is not None:
            android_ordinary_raw_evidence.reject_output_inside_artifact_root(
                destination.path, args.raw_manifest
            )
    except (
        OSError,
        EvidenceError,
        android_ordinary_raw_evidence.RawEvidenceError,
    ) as error:
        destination.close()
        print(
            f"Android ordinary producer refused unsafe output: {error}", file=sys.stderr
        )
        return 2

    try:
        source_sha = current_head_sha()
    except Exception as error:  # noqa: BLE001 - stale PASS must not survive
        if isinstance(error, EvidenceError):
            code = error.code
            reason = error.message
        else:
            code = "SOURCE_BINDING_FAILED"
            reason = f"unexpected source binding failure ({type(error).__name__})"
        write_canonical_json(
            args.output,
            all_failure_results(UNKNOWN_SOURCE_SHA, code=code, reason=reason),
            destination=destination,
        )
        print(
            f"Android ordinary producer failed before source binding: {error}",
            file=sys.stderr,
        )
        destination.close()
        return 2

    write_canonical_json(
        args.output,
        all_failure_results(
            source_sha,
            code="SOURCE_VALIDATION_PENDING",
            reason="clean source validation has not completed",
        ),
        destination=destination,
    )
    try:
        validated_source_sha = current_source_sha()
        if validated_source_sha != source_sha:
            raise EvidenceError(
                "SOURCE_CHANGED", "source HEAD changed during clean validation"
            )
        if args.raw_manifest is None:
            results = all_failure_results(
                source_sha,
                code="RAW_EVIDENCE_REQUIRED",
                reason="private raw bundle and exact app/test APKs are required",
            )
        else:
            provenance = android_ordinary_raw_evidence.validate_raw_bundle(
                args.raw_manifest,
                expected_source_sha=source_sha,
                app_apk=args.app_apk,
                test_apk=args.test_apk,
            )
            if current_source_sha() != source_sha:
                raise EvidenceError(
                    "SOURCE_CHANGED", "source changed during raw bundle verification"
                )
            results = semantic_failure_results(source_sha, provenance)
    except Exception as error:  # noqa: BLE001 - finalize every provenance failure
        if isinstance(error, EvidenceError):
            results = all_failure_results(
                source_sha, code=error.code, reason=error.message
            )
        elif isinstance(error, android_ordinary_raw_evidence.RawEvidenceError):
            results = all_failure_results(
                source_sha, code=error.code, reason=error.message
            )
        else:
            results = all_failure_results(
                source_sha,
                code="SOURCE_VALIDATION_FAILED",
                reason=f"unexpected source validation failure ({type(error).__name__})",
            )
    write_canonical_json(args.output, results, destination=destination)
    destination.close()
    print("Android ordinary release evidence is NO-SHIP:", file=sys.stderr)
    for gate_id in ORDINARY_GATE_IDS:
        print(
            f"  - {gate_id}: {results['gateResults'][gate_id]['reason']}",
            file=sys.stderr,
        )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
