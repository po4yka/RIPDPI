#!/usr/bin/env python3
"""Validate the private raw bundle for Android ordinary release gates.

This module pins artifact provenance and inventory before handing immutable
descriptors to the source-owned semantic oracles.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

try:
    from . import android_ordinary_semantic_oracles
except ImportError:  # Direct script/module loading through the CI directory.
    import android_ordinary_semantic_oracles


BUNDLE_VERSION = "android_ordinary_raw_bundle_v1"
SHA1_RE = re.compile(r"[0-9a-f]{40}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
MAX_MANIFEST_BYTES = 256 * 1024
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_APK_BYTES = 1024 * 1024 * 1024
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
ARTIFACT_KINDS = ("action-receipt", "packet-capture", "route-snapshot")
ARTIFACT_VANTAGES = {
    "action-receipt": "android-client",
    "packet-capture": "client-underlay",
    "route-snapshot": "android-client",
}
MAX_EVIDENCE_AGE_MS = 24 * 60 * 60 * 1000
MAX_CLOCK_SKEW_MS = 5 * 60 * 1000


@dataclass(frozen=True)
class ActionSpec:
    action_id: str
    gate_ids: tuple[str, ...]


@dataclass(frozen=True)
class ValidatedAction:
    spec: ActionSpec
    correlation_id: str
    window_started_at_ms: int
    window_finished_at_ms: int
    artifacts: dict[str, "PinnedArtifact"]


@dataclass
class PinnedArtifact:
    relative: str
    descriptor: int
    metadata: os.stat_result
    digest: str
    label: str

    def read(self) -> bytes:
        return _read_descriptor(self.descriptor, self.metadata, label=self.label)

    def revalidate(self, root_descriptor: int) -> None:
        try:
            current_descriptor = os.open(
                self.relative,
                os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW,
                dir_fd=root_descriptor,
            )
        except OSError as error:
            raise RawEvidenceError(
                "ARTIFACT_CHANGED", f"{self.label} path changed after verification"
            ) from error
        try:
            current = os.fstat(current_descriptor)
            if _stable_metadata(current) != _stable_metadata(self.metadata):
                raise RawEvidenceError(
                    "ARTIFACT_CHANGED",
                    f"{self.label} path changed after verification",
                )
            if (
                hashlib.sha256(
                    _read_descriptor(current_descriptor, current, label=self.label)
                ).hexdigest()
                != self.digest
            ):
                raise RawEvidenceError(
                    "ARTIFACT_CHANGED",
                    f"{self.label} content changed after verification",
                )
        finally:
            os.close(current_descriptor)

    def close(self) -> None:
        os.close(self.descriptor)


@dataclass
class ValidatedRawBundle:
    provenance: dict[str, Any]
    root_descriptor: int
    root_identity: tuple[int, int]
    expected_filenames: frozenset[str]
    artifacts: list[PinnedArtifact]
    actions: tuple[ValidatedAction, ...]
    source_sha: str
    app_apk_sha256: str
    test_apk_sha256: str
    expires_at_epoch_ms: int
    current_time_ms: Callable[[], int]

    def _require_root_state(self) -> None:
        root_metadata = os.fstat(self.root_descriptor)
        if (
            (root_metadata.st_dev, root_metadata.st_ino) != self.root_identity
            or not stat.S_ISDIR(root_metadata.st_mode)
            or stat.S_IMODE(root_metadata.st_mode) != 0o700
            or root_metadata.st_uid != os.getuid()
        ):
            raise RawEvidenceError(
                "ARTIFACT_ROOT_CHANGED",
                "raw artifact root identity, owner, or mode changed",
            )
        if set(os.listdir(self.root_descriptor)) != self.expected_filenames:
            raise RawEvidenceError(
                "INVENTORY_MISMATCH",
                "artifactRoot entries changed after verification",
            )

    def revalidate(self) -> None:
        if self.current_time_ms() > self.expires_at_epoch_ms:
            raise RawEvidenceError(
                "EVIDENCE_STALE", "raw artifact bundle expired during verification"
            )
        self._require_root_state()
        for artifact in self.artifacts:
            artifact.revalidate(self.root_descriptor)
        self._require_root_state()
        if self.current_time_ms() > self.expires_at_epoch_ms:
            raise RawEvidenceError(
                "EVIDENCE_STALE", "raw artifact bundle expired during verification"
            )

    def close(self) -> None:
        for artifact in self.artifacts:
            artifact.close()
        self.artifacts.clear()

    def evaluate_semantics(self) -> dict[str, Any]:
        self.revalidate()
        action_proofs: dict[str, Any] = {}
        gate_results: dict[str, dict[str, str]] = {}
        try:
            for action in self.actions:
                artifacts = {
                    kind: android_ordinary_semantic_oracles.ArtifactBytes(
                        kind=kind,
                        payload=artifact.read(),
                        sha256=artifact.digest,
                    )
                    for kind, artifact in action.artifacts.items()
                }
                proof = android_ordinary_semantic_oracles.evaluate_action(
                    android_ordinary_semantic_oracles.ActionContext(
                        action_id=action.spec.action_id,
                        gate_ids=action.spec.gate_ids,
                        correlation_id=action.correlation_id,
                        source_sha=self.source_sha,
                        app_apk_sha256=self.app_apk_sha256,
                        test_apk_sha256=self.test_apk_sha256,
                        window_started_at_ms=action.window_started_at_ms,
                        window_finished_at_ms=action.window_finished_at_ms,
                        artifacts=artifacts,
                    )
                )
                action_proofs[action.spec.action_id] = proof
                for gate_id in action.spec.gate_ids:
                    gate_results[gate_id] = {"state": "PASS"}
        except android_ordinary_semantic_oracles.OracleError as error:
            raise RawEvidenceError(error.code, error.message) from error
        self.revalidate()
        return {"actionProofs": action_proofs, "gateResults": gate_results}


ACTION_SPECS = (
    ActionSpec(
        "ipv4-only",
        (
            "ipv4only-no-ipv6-dns-address-route",
            "ipv4only-no-direct-ipv6",
            "ipv4only-blocked-ipv6-only-connect",
            "ipv4only-empty-or-blocked-aaaa",
        ),
    ),
    ActionSpec(
        "dual-stack",
        (
            "dualstack-default-route-through-tunnel",
            "dualstack-aaaa-through-tunnel",
        ),
    ),
    ActionSpec(
        "forced-revoke",
        ("killswitch-forced-disconnect",),
    ),
    ActionSpec(
        "core-fault",
        ("killswitch-core-crash",),
    ),
    ActionSpec(
        "wifi-lte-switch",
        ("killswitch-wifi-lte-switch",),
    ),
    ActionSpec(
        "sleep-wake",
        ("killswitch-sleep-wake",),
    ),
    ActionSpec(
        "android-always-on-block",
        ("killswitch-android-always-on-block",),
    ),
)


class RawEvidenceError(ValueError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def canonical_json_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode()


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise RawEvidenceError("MANIFEST_INVALID", f"duplicate key {key!r}")
        result[key] = value
    return result


def require_exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    actual = set(value)
    if actual != expected:
        raise RawEvidenceError(
            "MANIFEST_INVALID",
            f"{label} keys differ; missing={sorted(expected - actual)}, "
            f"extra={sorted(actual - expected)}",
        )


def require_digest(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise RawEvidenceError("MANIFEST_INVALID", f"{label} has invalid digest")
    return value


def require_epoch_ms(value: Any, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise RawEvidenceError(
            "MANIFEST_INVALID", f"{label} must be a positive integer"
        )
    return value


def _open_absolute_regular(
    path: Path,
    *,
    label: str,
    maximum: int,
    private: bool,
) -> tuple[int, os.stat_result]:
    if not path.is_absolute():
        raise RawEvidenceError("PATH_INVALID", f"{label} path must be absolute")
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW)
    except RawEvidenceError:
        raise
    except OSError as error:
        raise RawEvidenceError("PATH_INVALID", f"{label} is not readable") from error
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode):
        os.close(descriptor)
        raise RawEvidenceError("PATH_INVALID", f"{label} must be a regular file")
    if metadata.st_nlink != 1:
        os.close(descriptor)
        raise RawEvidenceError("PATH_INVALID", f"{label} must have one hard link")
    if private and stat.S_IMODE(metadata.st_mode) != 0o600:
        os.close(descriptor)
        raise RawEvidenceError("PRIVACY_INVALID", f"{label} mode must be 0600")
    if metadata.st_size <= 0 or metadata.st_size > maximum:
        os.close(descriptor)
        raise RawEvidenceError("SIZE_INVALID", f"{label} size is outside its bound")
    return descriptor, metadata


def _read_descriptor(descriptor: int, metadata: os.stat_result, *, label: str) -> bytes:
    os.lseek(descriptor, 0, os.SEEK_SET)
    with os.fdopen(descriptor, "rb", closefd=False) as source:
        raw = source.read(metadata.st_size + 1)
    if len(raw) != metadata.st_size or _stable_metadata(
        os.fstat(descriptor)
    ) != _stable_metadata(metadata):
        raise RawEvidenceError("ARTIFACT_CHANGED", f"{label} changed while read")
    return raw


def _stable_metadata(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def read_regular_file(path: Path, *, label: str, maximum: int, private: bool) -> bytes:
    descriptor, metadata = _open_absolute_regular(
        path, label=label, maximum=maximum, private=private
    )
    try:
        return _read_descriptor(descriptor, metadata, label=label)
    finally:
        os.close(descriptor)


def parse_private_manifest(raw: bytes) -> dict[str, Any]:
    try:
        manifest = json.loads(raw, object_pairs_hook=reject_duplicate_keys)
    except RawEvidenceError:
        raise
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RawEvidenceError(
            "MANIFEST_INVALID", "raw bundle manifest is not valid UTF-8 JSON"
        ) from error
    if not isinstance(manifest, dict):
        raise RawEvidenceError("MANIFEST_INVALID", "manifest must be an object")
    if raw != canonical_json_bytes(manifest):
        raise RawEvidenceError("MANIFEST_NONCANONICAL", "manifest is not canonical")
    return manifest


def load_private_manifest_descriptor(
    descriptor: int, metadata: os.stat_result
) -> tuple[dict[str, Any], bytes]:
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise RawEvidenceError(
            "PATH_INVALID", "raw bundle manifest must be a single-link regular file"
        )
    if stat.S_IMODE(metadata.st_mode) != 0o600:
        raise RawEvidenceError(
            "PRIVACY_INVALID", "raw bundle manifest mode must be 0600"
        )
    if metadata.st_size <= 0 or metadata.st_size > MAX_MANIFEST_BYTES:
        raise RawEvidenceError(
            "SIZE_INVALID", "raw bundle manifest size is outside its bound"
        )
    raw = _read_descriptor(descriptor, metadata, label="raw bundle manifest")
    return parse_private_manifest(raw), raw


def load_private_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    descriptor, metadata = _open_absolute_regular(
        path, label="raw bundle manifest", maximum=MAX_MANIFEST_BYTES, private=True
    )
    try:
        return load_private_manifest_descriptor(descriptor, metadata)
    finally:
        os.close(descriptor)


def sha256_descriptor(
    descriptor: int, metadata: os.stat_result, label: str, maximum: int = MAX_APK_BYTES
) -> str:
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise RawEvidenceError(
            "PATH_INVALID", f"{label} must be a single-link regular file"
        )
    if metadata.st_size <= 0 or metadata.st_size > maximum:
        raise RawEvidenceError("SIZE_INVALID", f"{label} size is outside its bound")
    digest = hashlib.sha256()
    total = 0
    os.lseek(descriptor, 0, os.SEEK_SET)
    with os.fdopen(descriptor, "rb", closefd=False) as source:
        while chunk := source.read(1024 * 1024):
            total += len(chunk)
            if total > metadata.st_size:
                raise RawEvidenceError("ARTIFACT_CHANGED", f"{label} grew while read")
            digest.update(chunk)
    if total != metadata.st_size or _stable_metadata(
        os.fstat(descriptor)
    ) != _stable_metadata(metadata):
        raise RawEvidenceError("ARTIFACT_CHANGED", f"{label} changed while read")
    return digest.hexdigest()


def sha256_file(path: Path, label: str) -> str:
    descriptor, metadata = _open_absolute_regular(
        path, label=label, maximum=MAX_APK_BYTES, private=False
    )
    try:
        return sha256_descriptor(descriptor, metadata, label)
    finally:
        os.close(descriptor)


def artifact_root_from_manifest(manifest: dict[str, Any]) -> Path:
    root_value = manifest.get("artifactRoot")
    if not isinstance(root_value, str) or not Path(root_value).is_absolute():
        raise RawEvidenceError(
            "OUTPUT_SAFETY_UNPROVEN",
            "artifactRoot must be absolute before results output can be written",
        )
    return Path(os.path.realpath(root_value))


def load_artifact_root_for_output(manifest_path: Path) -> Path:
    manifest, _ = load_private_manifest(manifest_path)
    return artifact_root_from_manifest(manifest)


def _open_artifact_root(path: Path) -> int:
    if not path.is_absolute():
        raise RawEvidenceError("PATH_INVALID", "artifactRoot must be absolute")
    try:
        descriptor = os.open(
            path, os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_DIRECTORY
        )
    except RawEvidenceError:
        raise
    except OSError as error:
        raise RawEvidenceError(
            "PATH_INVALID", "artifactRoot is not a readable directory"
        ) from error
    metadata = os.fstat(descriptor)
    if stat.S_IMODE(metadata.st_mode) != 0o700:
        os.close(descriptor)
        raise RawEvidenceError("PRIVACY_INVALID", "artifactRoot mode must be 0700")
    if metadata.st_uid != os.getuid():
        os.close(descriptor)
        raise RawEvidenceError(
            "PRIVACY_INVALID", "artifactRoot must be owned by the current user"
        )
    return descriptor


def validate_artifact_root_descriptor(descriptor: int) -> None:
    metadata = os.fstat(descriptor)
    if not stat.S_ISDIR(metadata.st_mode):
        raise RawEvidenceError("PATH_INVALID", "artifactRoot must be a directory")
    if stat.S_IMODE(metadata.st_mode) != 0o700:
        raise RawEvidenceError("PRIVACY_INVALID", "artifactRoot mode must be 0700")
    if metadata.st_uid != os.getuid():
        raise RawEvidenceError(
            "PRIVACY_INVALID", "artifactRoot must be owned by the current user"
        )


def _read_private_artifact(
    root_descriptor: int, entry: dict[str, Any], *, label: str
) -> PinnedArtifact:
    require_exact_keys(
        entry,
        {
            "kind",
            "path",
            "sha256",
            "sizeBytes",
            "vantage",
            "windowStartedAtEpochMs",
            "windowFinishedAtEpochMs",
        },
        label,
    )
    relative = entry["path"]
    if (
        not isinstance(relative, str)
        or not relative
        or Path(relative).name != relative
        or relative in (".", "..")
    ):
        raise RawEvidenceError(
            "PATH_INVALID", f"{label}.path must be a single relative filename"
        )
    size = entry["sizeBytes"]
    if isinstance(size, bool) or not isinstance(size, int) or size <= 0:
        raise RawEvidenceError("MANIFEST_INVALID", f"{label}.sizeBytes is invalid")
    expected_digest = require_digest(entry["sha256"], SHA256_RE, f"{label}.sha256")
    try:
        descriptor = os.open(
            relative,
            os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW,
            dir_fd=root_descriptor,
        )
    except OSError as error:
        raise RawEvidenceError(
            "ARTIFACT_MISSING", f"{label} is not readable"
        ) from error
    metadata = os.fstat(descriptor)
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        os.close(descriptor)
        raise RawEvidenceError(
            "PATH_INVALID", f"{label} must be a single-link regular file"
        )
    if stat.S_IMODE(metadata.st_mode) != 0o600:
        os.close(descriptor)
        raise RawEvidenceError("PRIVACY_INVALID", f"{label} mode must be 0600")
    if metadata.st_size <= 0 or metadata.st_size > MAX_ARTIFACT_BYTES:
        os.close(descriptor)
        raise RawEvidenceError("SIZE_INVALID", f"{label} size is outside its bound")
    try:
        raw = _read_descriptor(descriptor, metadata, label=label)
    except Exception:
        os.close(descriptor)
        raise
    if len(raw) != size:
        os.close(descriptor)
        raise RawEvidenceError("SIZE_MISMATCH", f"{label} size does not match manifest")
    if hashlib.sha256(raw).hexdigest() != expected_digest:
        os.close(descriptor)
        raise RawEvidenceError(
            "DIGEST_MISMATCH", f"{label} digest does not match manifest"
        )
    return PinnedArtifact(relative, descriptor, metadata, expected_digest, label)


def current_epoch_ms() -> int:
    return int(time.time() * 1000)


def validate_raw_bundle_pinned(
    manifest: dict[str, Any],
    manifest_raw: bytes,
    *,
    expected_source_sha: str,
    app_descriptor: int,
    app_metadata: os.stat_result,
    test_descriptor: int,
    test_metadata: os.stat_result,
    root_descriptor: int,
    now_epoch_ms: int | None = None,
) -> ValidatedRawBundle:
    require_digest(expected_source_sha, SHA1_RE, "expected sourceSha")
    validate_artifact_root_descriptor(root_descriptor)
    require_exact_keys(
        manifest,
        {
            "version",
            "sourceSha",
            "appApkSha256",
            "testApkSha256",
            "artifactRoot",
            "actions",
            "runId",
            "createdAtEpochMs",
        },
        "manifest",
    )
    if manifest["version"] != BUNDLE_VERSION:
        raise RawEvidenceError("MANIFEST_INVALID", "unexpected manifest version")
    source_sha = require_digest(manifest["sourceSha"], SHA1_RE, "sourceSha")
    if source_sha != expected_source_sha:
        raise RawEvidenceError("SOURCE_MISMATCH", "manifest sourceSha is stale")
    run_id = require_digest(manifest["runId"], SHA256_RE, "runId")
    created_at = require_epoch_ms(manifest["createdAtEpochMs"], "createdAtEpochMs")
    time_source = current_epoch_ms if now_epoch_ms is None else lambda: now_epoch_ms
    current_time = time_source()
    if created_at > current_time + MAX_CLOCK_SKEW_MS:
        raise RawEvidenceError(
            "CLOCK_INVALID", "manifest creation time is in the future"
        )
    if current_time - created_at > MAX_EVIDENCE_AGE_MS:
        raise RawEvidenceError("EVIDENCE_STALE", "raw artifact bundle is stale")
    app_digest = require_digest(manifest["appApkSha256"], SHA256_RE, "appApkSha256")
    test_digest = require_digest(manifest["testApkSha256"], SHA256_RE, "testApkSha256")
    if app_digest == test_digest:
        raise RawEvidenceError("APK_BINDING_INVALID", "app and test APKs must differ")
    if sha256_descriptor(app_descriptor, app_metadata, "app APK") != app_digest:
        raise RawEvidenceError("APK_DIGEST_MISMATCH", "app APK digest does not match")
    if sha256_descriptor(test_descriptor, test_metadata, "test APK") != test_digest:
        raise RawEvidenceError("APK_DIGEST_MISMATCH", "test APK digest does not match")

    if not isinstance(manifest["artifactRoot"], str):
        raise RawEvidenceError("MANIFEST_INVALID", "artifactRoot must be a string")
    pinned_artifacts: list[PinnedArtifact] = []
    validated_actions: list[ValidatedAction] = []
    try:
        actions = manifest["actions"]
        if not isinstance(actions, list) or len(actions) != len(ACTION_SPECS):
            raise RawEvidenceError(
                "INVENTORY_MISMATCH", "actions must exactly cover seven actions"
            )
        expected_filenames: set[str] = set()
        expires_at_epoch_ms = created_at + MAX_EVIDENCE_AGE_MS
        correlations: set[str] = set()
        total_bytes = 0
        for index, (action, spec) in enumerate(zip(actions, ACTION_SPECS, strict=True)):
            label = f"actions[{index}]"
            if not isinstance(action, dict):
                raise RawEvidenceError("MANIFEST_INVALID", f"{label} must be an object")
            require_exact_keys(
                action,
                {
                    "actionId",
                    "gateIds",
                    "artifacts",
                    "correlationId",
                    "windowStartedAtEpochMs",
                    "windowFinishedAtEpochMs",
                },
                label,
            )
            if action["actionId"] != spec.action_id or action["gateIds"] != list(
                spec.gate_ids
            ):
                raise RawEvidenceError(
                    "INVENTORY_MISMATCH",
                    f"{label} does not match source-owned action/gate inventory",
                )
            correlation = require_digest(
                action["correlationId"], SHA256_RE, f"{label}.correlationId"
            )
            if correlation == run_id or correlation in correlations:
                raise RawEvidenceError(
                    "CORRELATION_MISMATCH",
                    "each action correlationId must be unique and distinct from runId",
                )
            correlations.add(correlation)
            window_started = require_epoch_ms(
                action["windowStartedAtEpochMs"], f"{label}.windowStartedAtEpochMs"
            )
            window_finished = require_epoch_ms(
                action["windowFinishedAtEpochMs"], f"{label}.windowFinishedAtEpochMs"
            )
            if not window_started < window_finished <= created_at:
                raise RawEvidenceError(
                    "WINDOW_MISMATCH", f"{label} has an invalid observation window"
                )
            if current_time - window_started > MAX_EVIDENCE_AGE_MS:
                raise RawEvidenceError(
                    "EVIDENCE_STALE", f"{label} observation window is stale"
                )
            expires_at_epoch_ms = min(
                expires_at_epoch_ms, window_started + MAX_EVIDENCE_AGE_MS
            )
            artifacts = action["artifacts"]
            if not isinstance(artifacts, list) or len(artifacts) != len(ARTIFACT_KINDS):
                raise RawEvidenceError(
                    "INVENTORY_MISMATCH",
                    f"{label}.artifacts must cover exact raw artifact kinds",
                )
            action_artifacts: dict[str, PinnedArtifact] = {}
            for artifact_index, (artifact, expected_kind) in enumerate(
                zip(artifacts, ARTIFACT_KINDS, strict=True)
            ):
                artifact_label = f"{label}.artifacts[{artifact_index}]"
                if (
                    not isinstance(artifact, dict)
                    or artifact.get("kind") != expected_kind
                ):
                    raise RawEvidenceError(
                        "INVENTORY_MISMATCH",
                        f"{artifact_label} must be {expected_kind!r}",
                    )
                if artifact.get("vantage") != ARTIFACT_VANTAGES[expected_kind]:
                    raise RawEvidenceError(
                        "VANTAGE_MISMATCH",
                        f"{artifact_label} has an unexpected vantage",
                    )
                if (
                    artifact.get("windowStartedAtEpochMs") != window_started
                    or artifact.get("windowFinishedAtEpochMs") != window_finished
                ):
                    raise RawEvidenceError(
                        "WINDOW_MISMATCH",
                        f"{artifact_label} does not bind the action observation window",
                    )
                relative = artifact.get("path")
                if isinstance(relative, str) and relative in expected_filenames:
                    raise RawEvidenceError(
                        "INVENTORY_MISMATCH", "artifact filenames must be unique"
                    )
                pinned_artifact = _read_private_artifact(
                    root_descriptor, artifact, label=artifact_label
                )
                pinned_artifacts.append(pinned_artifact)
                action_artifacts[expected_kind] = pinned_artifact
                expected_filenames.add(relative)
                total_bytes += pinned_artifact.metadata.st_size
                if total_bytes > MAX_BUNDLE_BYTES:
                    raise RawEvidenceError(
                        "SIZE_INVALID", "raw artifact bundle exceeds its size bound"
                    )
            validated_actions.append(
                ValidatedAction(
                    spec=spec,
                    correlation_id=correlation,
                    window_started_at_ms=window_started,
                    window_finished_at_ms=window_finished,
                    artifacts=action_artifacts,
                )
            )
        actual_filenames = set(os.listdir(root_descriptor))
        if actual_filenames != expected_filenames:
            raise RawEvidenceError(
                "INVENTORY_MISMATCH",
                "artifactRoot entries do not exactly match the manifest inventory",
            )
        for pinned_artifact in pinned_artifacts:
            pinned_artifact.revalidate(root_descriptor)
    except Exception:
        for pinned_artifact in pinned_artifacts:
            pinned_artifact.close()
        raise

    # Rebind mutable inputs after the complete read. A future semantic oracle may
    # only consume a bundle whose source and APK identities remained stable.
    try:
        if (
            sha256_descriptor(app_descriptor, app_metadata, "app APK") != app_digest
            or sha256_descriptor(test_descriptor, test_metadata, "test APK")
            != test_digest
        ):
            raise RawEvidenceError("APK_CHANGED", "an APK changed during verification")
    except Exception:
        for pinned_artifact in pinned_artifacts:
            pinned_artifact.close()
        raise
    root_metadata = os.fstat(root_descriptor)
    return ValidatedRawBundle(
        provenance={
            "manifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
            "artifactCount": len(ACTION_SPECS) * len(ARTIFACT_KINDS),
            "actionCount": len(ACTION_SPECS),
            "semanticVerifier": android_ordinary_semantic_oracles.VERIFIER_VERSION,
        },
        root_descriptor=root_descriptor,
        root_identity=(root_metadata.st_dev, root_metadata.st_ino),
        expected_filenames=frozenset(expected_filenames),
        artifacts=pinned_artifacts,
        actions=tuple(validated_actions),
        source_sha=source_sha,
        app_apk_sha256=app_digest,
        test_apk_sha256=test_digest,
        expires_at_epoch_ms=expires_at_epoch_ms,
        current_time_ms=time_source,
    )


def validate_raw_bundle(
    manifest_path: Path,
    *,
    expected_source_sha: str,
    app_apk: Path,
    test_apk: Path,
    now_epoch_ms: int | None = None,
) -> dict[str, Any]:
    manifest_descriptor, manifest_metadata = _open_absolute_regular(
        manifest_path,
        label="raw bundle manifest",
        maximum=MAX_MANIFEST_BYTES,
        private=True,
    )
    app_descriptor = test_descriptor = root_descriptor = -1
    try:
        manifest, manifest_raw = load_private_manifest_descriptor(
            manifest_descriptor, manifest_metadata
        )
        app_descriptor, app_metadata = _open_absolute_regular(
            app_apk, label="app APK", maximum=MAX_APK_BYTES, private=False
        )
        test_descriptor, test_metadata = _open_absolute_regular(
            test_apk, label="test APK", maximum=MAX_APK_BYTES, private=False
        )
        root_value = manifest.get("artifactRoot")
        if not isinstance(root_value, str):
            raise RawEvidenceError("MANIFEST_INVALID", "artifactRoot must be a string")
        root_descriptor = _open_artifact_root(Path(root_value))
        validated = validate_raw_bundle_pinned(
            manifest,
            manifest_raw,
            expected_source_sha=expected_source_sha,
            app_descriptor=app_descriptor,
            app_metadata=app_metadata,
            test_descriptor=test_descriptor,
            test_metadata=test_metadata,
            root_descriptor=root_descriptor,
            now_epoch_ms=now_epoch_ms,
        )
        try:
            validated.revalidate()
            return validated.provenance
        finally:
            validated.close()
    finally:
        os.close(manifest_descriptor)
        if app_descriptor >= 0:
            os.close(app_descriptor)
        if test_descriptor >= 0:
            os.close(test_descriptor)
        if root_descriptor >= 0:
            os.close(root_descriptor)
