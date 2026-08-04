#!/usr/bin/env python3
"""Validate, classify, and safely purge bounded diagnostic evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import tarfile
from datetime import UTC, datetime, timedelta
from pathlib import Path, PurePosixPath
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_POLICY = ROOT / "quality/evidence-retention.json"
PCAP_SUFFIXES = {".pcap", ".pcapng"}
PCAP_MAGICS = {
    b"\xa1\xb2\xc3\xd4",
    b"\xd4\xc3\xb2\xa1",
    b"\xa1\xb2\x3c\x4d",
    b"\x4d\x3c\xb2\xa1",
    b"\x0a\x0d\x0d\x0a",
}
SENSITIVE_BINARY = re.compile(
    rb'''(?ix)
    ["']?(?:private[_ -]?key|password|secret|auth|token|ssid|bssid|imsi|operator|subscription)["']?
    \s*[:=]\s*["']?(?!<redacted>|null|false|true)[^"'\x00\s,}]{3,}
    '''
)
MAX_ARCHIVE_MEMBERS = 256
MAX_MEMBER_BYTES = 16 * 1024 * 1024
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MANIFEST_FIELDS = {
    "version", "artifact", "retentionClass", "createdUtc", "expiresUtc",
    "containsRawPcap", "localOnly", "sha256",
}


class EvidenceError(ValueError):
    pass


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvidenceError(f"{field} must be an object")
    return value


def validate_policy(path: Path = DEFAULT_POLICY) -> dict[str, Any]:
    policy = _object(json.loads(path.read_text(encoding="utf-8")), "policy")
    if policy.get("schemaVersion") != 1:
        raise EvidenceError("schemaVersion must be 1")
    if policy.get("manifestVersion") != "ripdpi_evidence_retention_v1":
        raise EvidenceError("manifestVersion must be ripdpi_evidence_retention_v1")
    classes = _object(policy.get("classes"), "classes")
    required = {
        "public-sanitized",
        "private-raw-pcap",
        "transient-candidate-download",
    }
    if set(classes) != required:
        raise EvidenceError(f"classes must be exactly {sorted(required)}")
    for name, raw in classes.items():
        entry = _object(raw, f"classes.{name}")
        if set(entry) != {"maxAgeHours", "allowRawPcap", "localOnly"}:
            raise EvidenceError(f"classes.{name} has unexpected fields")
        max_age = entry["maxAgeHours"]
        if not isinstance(max_age, int) or isinstance(max_age, bool) or not 1 <= max_age <= 720:
            raise EvidenceError(f"classes.{name}.maxAgeHours must be 1..720")
        if not isinstance(entry["allowRawPcap"], bool) or not isinstance(entry["localOnly"], bool):
            raise EvidenceError(f"classes.{name} booleans are invalid")
    if classes["public-sanitized"]["allowRawPcap"]:
        raise EvidenceError("public-sanitized must reject raw PCAP")
    if not classes["private-raw-pcap"]["localOnly"]:
        raise EvidenceError("private-raw-pcap must remain local-only")
    roots = policy.get("purgeRoots")
    if not isinstance(roots, list) or not roots:
        raise EvidenceError("purgeRoots must be a non-empty list")
    for root in roots:
        candidate = PurePosixPath(root) if isinstance(root, str) else PurePosixPath("/")
        if candidate.is_absolute() or ".." in candidate.parts or not candidate.parts:
            raise EvidenceError(f"unsafe purge root: {root!r}")
    return policy


def _members(archive_path: Path) -> list[tuple[str, bytes]]:
    members: list[tuple[str, bytes]] = []
    try:
        with tarfile.open(archive_path, "r:gz") as archive:
            archive_members = archive.getmembers()
            if len(archive_members) > MAX_ARCHIVE_MEMBERS:
                raise EvidenceError("archive member count limit exceeded")
            total_size = 0
            for info in archive_members:
                name = PurePosixPath(info.name)
                if name.is_absolute() or ".." in name.parts or info.issym() or info.islnk():
                    raise EvidenceError(f"unsafe archive member: {info.name}")
                if not info.isfile():
                    continue
                if info.size < 0 or info.size > MAX_MEMBER_BYTES:
                    raise EvidenceError(f"archive member size limit exceeded: {info.name}")
                total_size += info.size
                if total_size > MAX_ARCHIVE_BYTES:
                    raise EvidenceError("archive expanded size limit exceeded")
                source = archive.extractfile(info)
                if source is None:
                    raise EvidenceError(f"could not read archive member: {info.name}")
                members.append((info.name, source.read()))
    except (tarfile.TarError, OSError) as error:
        raise EvidenceError(f"invalid evidence archive: {archive_path}") from error
    return members


def check_archive(
    archive_path: Path, policy_path: Path = DEFAULT_POLICY, retention_class: str = "public-sanitized"
) -> None:
    policy = validate_policy(policy_path)
    classes = policy["classes"]
    if retention_class not in classes:
        raise EvidenceError(f"unknown retention class: {retention_class}")
    allow_pcap = classes[retention_class]["allowRawPcap"]
    for name, payload in _members(archive_path):
        suffix = PurePosixPath(name).suffix.lower()
        capture = suffix in PCAP_SUFFIXES or payload[:4] in PCAP_MAGICS
        if capture:
            if not allow_pcap:
                raise EvidenceError(f"public evidence contains raw packet capture: {name}")
            if not _valid_capture(payload):
                raise EvidenceError(f"invalid PCAP evidence: {name}")
        elif retention_class == "public-sanitized" and (
            SENSITIVE_BINARY.search(name.encode("utf-8", errors="replace"))
            or SENSITIVE_BINARY.search(payload)
        ):
            raise EvidenceError(f"public evidence contains sensitive binary payload: {name}")


def _valid_capture(payload: bytes) -> bool:
    magic = payload[:4]
    if magic == b"\x0a\x0d\x0d\x0a":
        if len(payload) < 28 or payload[8:12] not in {
            b"\x1a\x2b\x3c\x4d", b"\x4d\x3c\x2b\x1a"
        }:
            return False
        endian = ">" if payload[8:12] == b"\x1a\x2b\x3c\x4d" else "<"
        block_length = struct.unpack(f"{endian}I", payload[4:8])[0]
        return (
            block_length >= 28
            and block_length % 4 == 0
            and block_length <= len(payload)
            and struct.unpack(f"{endian}I", payload[block_length - 4:block_length])[0]
            == block_length
        )
    endian = {
        b"\xd4\xc3\xb2\xa1": "<",
        b"\x4d\x3c\xb2\xa1": "<",
        b"\xa1\xb2\xc3\xd4": ">",
        b"\xa1\xb2\x3c\x4d": ">",
    }.get(magic)
    if endian is None or len(payload) < 24:
        return False
    major, minor, _zone, _accuracy, snaplen, _network = struct.unpack(
        f"{endian}HHiiII", payload[4:24]
    )
    return (major, minor) == (2, 4) and 1 <= snaplen <= MAX_MEMBER_BYTES


def check_directory(
    directory: Path,
    policy_path: Path = DEFAULT_POLICY,
    retention_class: str = "public-sanitized",
) -> None:
    policy = validate_policy(policy_path)
    if retention_class not in policy["classes"]:
        raise EvidenceError(f"unknown retention class: {retention_class}")
    exact = directory.resolve(strict=True)
    if not exact.is_dir():
        raise EvidenceError(f"evidence directory does not exist: {directory}")
    file_count = 0
    total_size = 0
    for current, directories, files in os.walk(exact, followlinks=False):
        current_path = Path(current)
        for name in directories:
            if (current_path / name).is_symlink():
                raise EvidenceError(f"unsafe evidence directory symlink: {current_path / name}")
        for name in files:
            path = current_path / name
            if path.is_symlink() or not path.is_file():
                raise EvidenceError(f"unsafe evidence file: {path}")
            file_count += 1
            if file_count > MAX_ARCHIVE_MEMBERS:
                raise EvidenceError("evidence file count limit exceeded")
            if path.name.endswith((".tar.gz", ".tgz")):
                check_archive(path, policy_path, retention_class)
                continue
            size = path.stat().st_size
            if size > MAX_MEMBER_BYTES:
                raise EvidenceError(f"evidence file size limit exceeded: {path}")
            total_size += size
            if total_size > MAX_ARCHIVE_BYTES:
                raise EvidenceError("evidence expanded size limit exceeded")
            payload = path.read_bytes()
            suffix = path.suffix.lower()
            capture = suffix in PCAP_SUFFIXES or payload[:4] in PCAP_MAGICS
            if capture and not policy["classes"][retention_class]["allowRawPcap"]:
                raise EvidenceError(f"public evidence contains raw packet capture: {path}")
            if capture and not _valid_capture(payload):
                raise EvidenceError(f"invalid PCAP evidence: {path}")
            relative = str(path.relative_to(exact)).encode("utf-8", errors="replace")
            if retention_class == "public-sanitized" and (
                SENSITIVE_BINARY.search(relative) or SENSITIVE_BINARY.search(payload)
            ):
                raise EvidenceError(f"public evidence contains sensitive binary payload: {path}")


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise EvidenceError("timestamps must include a timezone")
    return value.astimezone(UTC)


def _format(value: datetime) -> str:
    return _utc(value).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def create_manifest(
    policy_path: Path,
    retention_class: str,
    artifact: Path,
    created: datetime | None = None,
) -> dict[str, Any]:
    policy = validate_policy(policy_path)
    if retention_class not in policy["classes"]:
        raise EvidenceError(f"unknown retention class: {retention_class}")
    check_archive(artifact, policy_path, retention_class)
    created_utc = _utc(created or datetime.now(UTC))
    max_age = policy["classes"][retention_class]["maxAgeHours"]
    contains_pcap = any(
        PurePosixPath(name).suffix.lower() in PCAP_SUFFIXES or payload[:4] in PCAP_MAGICS
        for name, payload in _members(artifact)
    )
    return {
        "version": policy["manifestVersion"],
        "artifact": artifact.name,
        "retentionClass": retention_class,
        "createdUtc": _format(created_utc),
        "expiresUtc": _format(created_utc + timedelta(hours=max_age)),
        "containsRawPcap": contains_pcap,
        "localOnly": policy["classes"][retention_class]["localOnly"],
        "sha256": _sha256_file(artifact),
    }


def _parse_utc(value: Any) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise EvidenceError("expiresUtc must be a UTC timestamp")
    try:
        return datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as error:
        raise EvidenceError("expiresUtc is malformed") from error


def _validate_manifest(
    manifest: dict[str, Any], artifact: Path, policy: dict[str, Any], policy_path: Path
) -> datetime:
    if set(manifest) != MANIFEST_FIELDS:
        raise EvidenceError("retention manifest fields do not match the supported schema")
    if manifest["version"] != policy["manifestVersion"]:
        raise EvidenceError("unknown retention manifest version")
    if manifest["artifact"] != artifact.name:
        raise EvidenceError("retention manifest artifact does not match its sidecar")
    retention_class = manifest["retentionClass"]
    classes = policy["classes"]
    if retention_class not in classes:
        raise EvidenceError("unknown retention class in manifest")
    if manifest["localOnly"] is not classes[retention_class]["localOnly"]:
        raise EvidenceError("manifest localOnly does not match retention class")
    created = _parse_utc(manifest["createdUtc"])
    expires = _parse_utc(manifest["expiresUtc"])
    expected_expiry = created + timedelta(hours=classes[retention_class]["maxAgeHours"])
    if expires != expected_expiry:
        raise EvidenceError("manifest expiry does not match retention policy")
    if not isinstance(manifest["containsRawPcap"], bool):
        raise EvidenceError("manifest containsRawPcap must be boolean")
    digest = manifest["sha256"]
    if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
        raise EvidenceError("manifest digest is malformed")
    actual_digest = _sha256_file(artifact)
    if digest != actual_digest:
        raise EvidenceError("managed artifact digest does not match manifest")
    check_archive(artifact, policy_path, retention_class)
    contains_pcap = any(
        PurePosixPath(name).suffix.lower() in PCAP_SUFFIXES or payload[:4] in PCAP_MAGICS
        for name, payload in _members(artifact)
    )
    if manifest["containsRawPcap"] is not contains_pcap:
        raise EvidenceError("manifest PCAP declaration does not match artifact")
    return expires


def purge_expired(
    root: Path,
    now: datetime | None = None,
    *,
    dry_run: bool,
    policy_path: Path = DEFAULT_POLICY,
) -> list[Path]:
    exact_root = root.resolve(strict=True)
    if exact_root == Path(exact_root.anchor):
        raise EvidenceError("refusing to purge a filesystem root")
    now_utc = _utc(now or datetime.now(UTC))
    policy = validate_policy(policy_path)
    removed: list[Path] = []
    for sidecar in sorted(exact_root.glob("*.retention.json")):
        if sidecar.is_symlink() or not sidecar.is_file():
            raise EvidenceError(f"unsafe retention sidecar: {sidecar}")
        manifest = _object(json.loads(sidecar.read_text(encoding="utf-8")), str(sidecar))
        artifact_name = manifest.get("artifact")
        if not isinstance(artifact_name, str) or Path(artifact_name).name != artifact_name:
            raise EvidenceError(f"unsafe managed artifact name: {artifact_name!r}")
        artifact = exact_root / artifact_name
        if artifact.is_symlink():
            raise EvidenceError(f"refusing to purge symlinked artifact: {artifact}")
        if not artifact.is_file():
            raise EvidenceError(f"managed artifact is missing or not regular: {artifact}")
        expires = _validate_manifest(manifest, artifact, policy, policy_path)
        if expires > now_utc:
            continue
        if artifact.is_file():
            removed.append(artifact)
            if not dry_run:
                artifact.unlink()
        if not dry_run:
            sidecar.unlink()
    return removed


def _require_policy_root(root: Path, policy_path: Path) -> Path:
    repo = policy_path.resolve().parents[1]
    requested = Path(os.path.abspath(root))
    allowed = {repo / relative for relative in validate_policy(policy_path)["purgeRoots"]}
    if requested not in allowed:
        raise EvidenceError(f"purge root is not declared by policy: {requested}")
    relative = requested.relative_to(repo)
    current = repo
    for part in relative.parts:
        current /= part
        if current.is_symlink():
            raise EvidenceError(f"purge root contains a symlink: {current}")
    exact_root = requested.resolve(strict=True)
    if not exact_root.is_relative_to(repo.resolve()):
        raise EvidenceError("purge root escapes the repository")
    return exact_root


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("validate")
    check = subparsers.add_parser("check-archive")
    check.add_argument("archive", type=Path)
    check.add_argument("--retention-class", required=True)
    directory = subparsers.add_parser("check-directory")
    directory.add_argument("directory", type=Path)
    directory.add_argument("--retention-class", required=True)
    manifest = subparsers.add_parser("write-manifest")
    manifest.add_argument("archive", type=Path)
    manifest.add_argument("--retention-class", required=True)
    purge = subparsers.add_parser("purge")
    purge.add_argument("root", type=Path)
    purge.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    try:
        if args.command == "validate":
            validate_policy(args.policy)
        elif args.command == "check-archive":
            check_archive(args.archive, args.policy, args.retention_class)
        elif args.command == "check-directory":
            check_directory(args.directory, args.policy, args.retention_class)
        elif args.command == "write-manifest":
            payload = create_manifest(args.policy, args.retention_class, args.archive)
            sidecar = args.archive.with_name(args.archive.name + ".retention.json")
            sidecar.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print(sidecar)
        else:
            exact_root = _require_policy_root(args.root, args.policy)
            for artifact in purge_expired(
                exact_root, dry_run=args.dry_run, policy_path=args.policy
            ):
                print(artifact)
    except (EvidenceError, json.JSONDecodeError, OSError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
