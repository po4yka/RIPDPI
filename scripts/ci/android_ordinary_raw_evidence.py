#!/usr/bin/env python3
"""Validate the private raw bundle for Android ordinary release gates.

This module owns only artifact provenance and inventory. It deliberately does
not interpret packet captures, route snapshots, or action receipts as proof of
a gate result; those source-owned semantic oracles have not been implemented.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from dataclasses import dataclass
from pathlib import Path
from typing import Any


BUNDLE_VERSION = "android_ordinary_raw_bundle_v1"
SHA1_RE = re.compile(r"[0-9a-f]{40}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
MAX_MANIFEST_BYTES = 256 * 1024
MAX_ARTIFACT_BYTES = 64 * 1024 * 1024
MAX_APK_BYTES = 1024 * 1024 * 1024
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
ARTIFACT_KINDS = ("action-receipt", "packet-capture", "route-snapshot")


@dataclass(frozen=True)
class ActionSpec:
    action_id: str
    gate_ids: tuple[str, ...]
    blocker_code: str


ACTION_SPECS = (
    ActionSpec(
        "ipv4-only",
        (
            "ipv4only-no-ipv6-dns-address-route",
            "ipv4only-no-direct-ipv6",
            "ipv4only-blocked-ipv6-only-connect",
            "ipv4only-empty-or-blocked-aaaa",
        ),
        "SEMANTIC_IPV4_ONLY_ORACLE_UNAVAILABLE",
    ),
    ActionSpec(
        "dual-stack",
        (
            "dualstack-default-route-through-tunnel",
            "dualstack-aaaa-through-tunnel",
        ),
        "SEMANTIC_DUAL_STACK_ORACLE_UNAVAILABLE",
    ),
    ActionSpec(
        "forced-revoke",
        ("killswitch-forced-disconnect",),
        "SEMANTIC_FORCED_REVOKE_ORACLE_UNAVAILABLE",
    ),
    ActionSpec(
        "core-fault",
        ("killswitch-core-crash",),
        "SEMANTIC_CORE_FAULT_ORACLE_UNAVAILABLE",
    ),
    ActionSpec(
        "wifi-lte-switch",
        ("killswitch-wifi-lte-switch",),
        "SEMANTIC_WIFI_LTE_SWITCH_ORACLE_UNAVAILABLE",
    ),
    ActionSpec(
        "sleep-wake",
        ("killswitch-sleep-wake",),
        "SEMANTIC_SLEEP_WAKE_ORACLE_UNAVAILABLE",
    ),
    ActionSpec(
        "android-always-on-block",
        ("killswitch-android-always-on-block",),
        "SEMANTIC_ANDROID_ALWAYS_ON_BLOCK_ORACLE_UNAVAILABLE",
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
    with os.fdopen(descriptor, "rb", closefd=False) as source:
        raw = source.read(metadata.st_size + 1)
    after = os.fstat(descriptor)
    stable_before = (
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
    stable_after = (
        after.st_dev,
        after.st_ino,
        after.st_mode,
        after.st_nlink,
        after.st_uid,
        after.st_gid,
        after.st_size,
        after.st_mtime_ns,
        after.st_ctime_ns,
    )
    if len(raw) != metadata.st_size or stable_after != stable_before:
        raise RawEvidenceError("ARTIFACT_CHANGED", f"{label} changed while read")
    return raw


def read_regular_file(path: Path, *, label: str, maximum: int, private: bool) -> bytes:
    descriptor, metadata = _open_absolute_regular(
        path, label=label, maximum=maximum, private=private
    )
    try:
        return _read_descriptor(descriptor, metadata, label=label)
    finally:
        os.close(descriptor)


def load_private_manifest(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = read_regular_file(
        path, label="raw bundle manifest", maximum=MAX_MANIFEST_BYTES, private=True
    )
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
    return manifest, raw


def sha256_file(path: Path, label: str) -> str:
    raw = read_regular_file(path, label=label, maximum=MAX_APK_BYTES, private=False)
    return hashlib.sha256(raw).hexdigest()


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


def _read_private_artifact(
    root_descriptor: int, entry: dict[str, Any], *, label: str
) -> bytes:
    require_exact_keys(entry, {"kind", "path", "sha256", "sizeBytes"}, label)
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
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
            raise RawEvidenceError(
                "PATH_INVALID", f"{label} must be a single-link regular file"
            )
        if stat.S_IMODE(metadata.st_mode) != 0o600:
            raise RawEvidenceError("PRIVACY_INVALID", f"{label} mode must be 0600")
        if metadata.st_size <= 0 or metadata.st_size > MAX_ARTIFACT_BYTES:
            raise RawEvidenceError("SIZE_INVALID", f"{label} size is outside its bound")
        raw = _read_descriptor(descriptor, metadata, label=label)
    finally:
        os.close(descriptor)
    if len(raw) != size:
        raise RawEvidenceError("SIZE_MISMATCH", f"{label} size does not match manifest")
    if hashlib.sha256(raw).hexdigest() != expected_digest:
        raise RawEvidenceError(
            "DIGEST_MISMATCH", f"{label} digest does not match manifest"
        )
    return raw


def semantic_blockers_by_gate() -> dict[str, str]:
    return {
        gate_id: spec.blocker_code for spec in ACTION_SPECS for gate_id in spec.gate_ids
    }


def validate_raw_bundle(
    manifest_path: Path,
    *,
    expected_source_sha: str,
    app_apk: Path,
    test_apk: Path,
) -> dict[str, Any]:
    require_digest(expected_source_sha, SHA1_RE, "expected sourceSha")
    manifest, manifest_raw = load_private_manifest(manifest_path)
    require_exact_keys(
        manifest,
        {
            "version",
            "sourceSha",
            "appApkSha256",
            "testApkSha256",
            "artifactRoot",
            "actions",
        },
        "manifest",
    )
    if manifest["version"] != BUNDLE_VERSION:
        raise RawEvidenceError("MANIFEST_INVALID", "unexpected manifest version")
    source_sha = require_digest(manifest["sourceSha"], SHA1_RE, "sourceSha")
    if source_sha != expected_source_sha:
        raise RawEvidenceError("SOURCE_MISMATCH", "manifest sourceSha is stale")
    app_digest = require_digest(manifest["appApkSha256"], SHA256_RE, "appApkSha256")
    test_digest = require_digest(manifest["testApkSha256"], SHA256_RE, "testApkSha256")
    if app_digest == test_digest:
        raise RawEvidenceError("APK_BINDING_INVALID", "app and test APKs must differ")
    if sha256_file(app_apk, "app APK") != app_digest:
        raise RawEvidenceError("APK_DIGEST_MISMATCH", "app APK digest does not match")
    if sha256_file(test_apk, "test APK") != test_digest:
        raise RawEvidenceError("APK_DIGEST_MISMATCH", "test APK digest does not match")

    root_value = manifest["artifactRoot"]
    if not isinstance(root_value, str):
        raise RawEvidenceError("MANIFEST_INVALID", "artifactRoot must be a string")
    root_descriptor = _open_artifact_root(Path(root_value))
    try:
        actions = manifest["actions"]
        if not isinstance(actions, list) or len(actions) != len(ACTION_SPECS):
            raise RawEvidenceError(
                "INVENTORY_MISMATCH", "actions must exactly cover seven actions"
            )
        expected_filenames: set[str] = set()
        total_bytes = 0
        for index, (action, spec) in enumerate(zip(actions, ACTION_SPECS, strict=True)):
            label = f"actions[{index}]"
            if not isinstance(action, dict):
                raise RawEvidenceError("MANIFEST_INVALID", f"{label} must be an object")
            require_exact_keys(action, {"actionId", "gateIds", "artifacts"}, label)
            if action["actionId"] != spec.action_id or action["gateIds"] != list(
                spec.gate_ids
            ):
                raise RawEvidenceError(
                    "INVENTORY_MISMATCH",
                    f"{label} does not match source-owned action/gate inventory",
                )
            artifacts = action["artifacts"]
            if not isinstance(artifacts, list) or len(artifacts) != len(ARTIFACT_KINDS):
                raise RawEvidenceError(
                    "INVENTORY_MISMATCH",
                    f"{label}.artifacts must cover exact raw artifact kinds",
                )
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
                relative = artifact.get("path")
                if isinstance(relative, str) and relative in expected_filenames:
                    raise RawEvidenceError(
                        "INVENTORY_MISMATCH", "artifact filenames must be unique"
                    )
                raw = _read_private_artifact(
                    root_descriptor, artifact, label=artifact_label
                )
                expected_filenames.add(relative)
                total_bytes += len(raw)
                if total_bytes > MAX_BUNDLE_BYTES:
                    raise RawEvidenceError(
                        "SIZE_INVALID", "raw artifact bundle exceeds its size bound"
                    )
        actual_filenames = set(os.listdir(root_descriptor))
        if actual_filenames != expected_filenames:
            raise RawEvidenceError(
                "INVENTORY_MISMATCH",
                "artifactRoot entries do not exactly match the manifest inventory",
            )
    finally:
        os.close(root_descriptor)

    # Rebind mutable inputs after the complete read. A future semantic oracle may
    # only consume a bundle whose source and APK identities remained stable.
    if (
        sha256_file(app_apk, "app APK") != app_digest
        or sha256_file(test_apk, "test APK") != test_digest
    ):
        raise RawEvidenceError("APK_CHANGED", "an APK changed during verification")
    return {
        "manifestSha256": hashlib.sha256(manifest_raw).hexdigest(),
        "artifactCount": len(ACTION_SPECS) * len(ARTIFACT_KINDS),
        "actionCount": len(ACTION_SPECS),
        "semanticBlockers": semantic_blockers_by_gate(),
    }
