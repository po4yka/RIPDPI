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
        self,
        path: Path,
        parent_fd: int,
        parent_identity: tuple[int, ...],
        leaf_fd: int,
        leaf_identity: tuple[int, int],
    ) -> None:
        self.path = path
        self.parent_fd = parent_fd
        self.parent_identity = parent_identity
        self.leaf_fd = leaf_fd
        self.leaf_identity = leaf_identity

    def close(self) -> None:
        os.close(self.leaf_fd)
        os.close(self.parent_fd)


def directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
    )


def path_is_within(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def open_output_destination(
    path: Path, *, forbidden_root: Path | None = None
) -> OutputDestination:
    if not path.is_absolute():
        raise EvidenceError("OUTPUT_PATH_INVALID", "results output must be absolute")
    if forbidden_root is not None and path_is_within(
        Path(os.path.realpath(path)), forbidden_root
    ):
        raise EvidenceError(
            "OUTPUT_INSIDE_ARTIFACT_ROOT",
            "results output must be outside the private raw artifact root",
        )
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
        resolved_output = resolved_parent / path.name
        if forbidden_root is not None and path_is_within(
            resolved_output, forbidden_root
        ):
            raise EvidenceError(
                "OUTPUT_INSIDE_ARTIFACT_ROOT",
                "results output must be outside the private raw artifact root",
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
        if output_metadata is None:
            leaf_fd = os.open(
                path.name,
                os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_CLOEXEC | os.O_NOFOLLOW,
                0o600,
                dir_fd=parent_fd,
            )
        else:
            leaf_fd = os.open(
                path.name,
                os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW,
                dir_fd=parent_fd,
            )
        leaf_metadata = os.fstat(leaf_fd)
        if output_metadata is not None and (
            leaf_metadata.st_dev,
            leaf_metadata.st_ino,
        ) != (output_metadata.st_dev, output_metadata.st_ino):
            os.close(leaf_fd)
            raise EvidenceError(
                "OUTPUT_LEAF_CHANGED", "results output changed while opened"
            )
        return OutputDestination(
            path=resolved_output,
            parent_fd=parent_fd,
            parent_identity=directory_identity(parent_metadata),
            leaf_fd=leaf_fd,
            leaf_identity=(leaf_metadata.st_dev, leaf_metadata.st_ino),
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
    try:
        if directory_identity(os.fstat(target.parent_fd)) != target.parent_identity:
            raise EvidenceError(
                "OUTPUT_PATH_INVALID", "results output parent metadata changed"
            )
        try:
            leaf_metadata = os.stat(
                path.name, dir_fd=target.parent_fd, follow_symlinks=False
            )
        except FileNotFoundError as error:
            raise EvidenceError(
                "OUTPUT_LEAF_CHANGED", "results output was removed while held"
            ) from error
        if (leaf_metadata.st_dev, leaf_metadata.st_ino) != target.leaf_identity:
            raise EvidenceError(
                "OUTPUT_LEAF_CHANGED", "results output was replaced while held"
            )
        payload = canonical_json_bytes(value)
        os.ftruncate(target.leaf_fd, 0)
        os.lseek(target.leaf_fd, 0, os.SEEK_SET)
        offset = 0
        while offset < len(payload):
            offset += os.write(target.leaf_fd, payload[offset:])
        os.fchmod(target.leaf_fd, 0o600)
        os.fsync(target.leaf_fd)
        final_metadata = os.stat(
            path.name, dir_fd=target.parent_fd, follow_symlinks=False
        )
        if (final_metadata.st_dev, final_metadata.st_ino) != target.leaf_identity:
            raise EvidenceError(
                "OUTPUT_LEAF_CHANGED", "results output changed during publication"
            )
    finally:
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
            source_metadata = source.stat()
            aliases = destination.leaf_identity == (
                source_metadata.st_dev,
                source_metadata.st_ino,
            )
        except OSError:
            aliases = False
        if aliases:
            raise EvidenceError(
                "OUTPUT_ALIASES_INPUT", "results output aliases an evidence input"
            )


def reject_output_aliases_before_reservation(
    output: Path, inputs: tuple[Path, ...]
) -> None:
    output_path = Path(os.path.realpath(output))
    try:
        output_metadata = output.stat()
    except OSError:
        output_metadata = None
    for source in inputs:
        if output_path == Path(os.path.realpath(source)):
            raise EvidenceError(
                "OUTPUT_ALIASES_INPUT", "results output aliases an evidence input"
            )
        if output_metadata is None:
            continue
        try:
            source_metadata = source.stat()
        except OSError:
            continue
        if (output_metadata.st_dev, output_metadata.st_ino) == (
            source_metadata.st_dev,
            source_metadata.st_ino,
        ):
            raise EvidenceError(
                "OUTPUT_ALIASES_INPUT", "results output aliases an evidence input"
            )


def publish_results(
    destination: OutputDestination, path: Path, value: dict[str, Any]
) -> bool:
    try:
        write_canonical_json(path, value, destination=destination)
        return True
    except (OSError, EvidenceError) as error:
        print(
            f"Android ordinary producer refused changed output: {error}",
            file=sys.stderr,
        )
        return False


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

    destination: OutputDestination | None = None
    try:
        inputs = tuple(value for value in raw_arguments if value is not None)
        reject_output_aliases_before_reservation(args.output, inputs)
        artifact_root = None
        if args.raw_manifest is not None:
            artifact_root = android_ordinary_raw_evidence.load_artifact_root_for_output(
                args.raw_manifest
            )
        destination = open_output_destination(args.output, forbidden_root=artifact_root)
        reject_output_input_aliases(destination, inputs)
    except (
        OSError,
        EvidenceError,
        android_ordinary_raw_evidence.RawEvidenceError,
    ) as error:
        if destination is not None:
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
        if not publish_results(
            destination,
            args.output,
            all_failure_results(UNKNOWN_SOURCE_SHA, code=code, reason=reason),
        ):
            destination.close()
            return 2
        print(
            f"Android ordinary producer failed before source binding: {error}",
            file=sys.stderr,
        )
        destination.close()
        return 2

    if not publish_results(
        destination,
        args.output,
        all_failure_results(
            source_sha,
            code="SOURCE_VALIDATION_PENDING",
            reason="clean source validation has not completed",
        ),
    ):
        destination.close()
        return 2
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
    if not publish_results(destination, args.output, results):
        destination.close()
        return 2
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
