#!/usr/bin/env python3
"""Validate, classify, and safely purge bounded diagnostic evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
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
    rb"(?i)(?:private[_ -]?key|password|secret|auth|token|imsi|subscription)\s*[:=]\s*(?!<redacted>)[^\x00\s]{3,}"
)


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
            for info in archive.getmembers():
                name = PurePosixPath(info.name)
                if name.is_absolute() or ".." in name.parts or info.issym() or info.islnk():
                    raise EvidenceError(f"unsafe archive member: {info.name}")
                if not info.isfile():
                    continue
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
        if suffix in PCAP_SUFFIXES:
            if not allow_pcap:
                raise EvidenceError(f"public evidence contains raw packet capture: {name}")
            if len(payload) < 24 or payload[:4] not in PCAP_MAGICS:
                raise EvidenceError(f"invalid PCAP evidence: {name}")
        elif retention_class == "public-sanitized" and SENSITIVE_BINARY.search(payload):
            raise EvidenceError(f"public evidence contains sensitive binary payload: {name}")


def _utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        raise EvidenceError("timestamps must include a timezone")
    return value.astimezone(UTC)


def _format(value: datetime) -> str:
    return _utc(value).replace(microsecond=0).isoformat().replace("+00:00", "Z")


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
    contains_pcap = any(PurePosixPath(name).suffix.lower() in PCAP_SUFFIXES for name, _ in _members(artifact))
    return {
        "version": policy["manifestVersion"],
        "artifact": artifact.name,
        "retentionClass": retention_class,
        "createdUtc": _format(created_utc),
        "expiresUtc": _format(created_utc + timedelta(hours=max_age)),
        "containsRawPcap": contains_pcap,
        "localOnly": policy["classes"][retention_class]["localOnly"],
        "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
    }


def _parse_utc(value: Any) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise EvidenceError("expiresUtc must be a UTC timestamp")
    try:
        return datetime.fromisoformat(value.removesuffix("Z") + "+00:00")
    except ValueError as error:
        raise EvidenceError("expiresUtc is malformed") from error


def purge_expired(root: Path, now: datetime | None = None, *, dry_run: bool) -> list[Path]:
    exact_root = root.resolve(strict=True)
    if exact_root == Path(exact_root.anchor):
        raise EvidenceError("refusing to purge a filesystem root")
    now_utc = _utc(now or datetime.now(UTC))
    removed: list[Path] = []
    for sidecar in sorted(exact_root.glob("*.retention.json")):
        if sidecar.is_symlink() or not sidecar.is_file():
            raise EvidenceError(f"unsafe retention sidecar: {sidecar}")
        manifest = _object(json.loads(sidecar.read_text(encoding="utf-8")), str(sidecar))
        if manifest.get("version") != "ripdpi_evidence_retention_v1":
            raise EvidenceError(f"unknown retention manifest version: {sidecar}")
        artifact_name = manifest.get("artifact")
        if not isinstance(artifact_name, str) or Path(artifact_name).name != artifact_name:
            raise EvidenceError(f"unsafe managed artifact name: {artifact_name!r}")
        artifact = exact_root / artifact_name
        if artifact.is_symlink():
            raise EvidenceError(f"refusing to purge symlinked artifact: {artifact}")
        if _parse_utc(manifest.get("expiresUtc")) > now_utc:
            continue
        if artifact.is_file():
            removed.append(artifact)
            if not dry_run:
                artifact.unlink()
        if not dry_run:
            sidecar.unlink()
    return removed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, default=DEFAULT_POLICY)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("validate")
    check = subparsers.add_parser("check-archive")
    check.add_argument("archive", type=Path)
    check.add_argument("--retention-class", required=True)
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
        elif args.command == "write-manifest":
            payload = create_manifest(args.policy, args.retention_class, args.archive)
            sidecar = args.archive.with_name(args.archive.name + ".retention.json")
            sidecar.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            print(sidecar)
        else:
            for artifact in purge_expired(args.root, dry_run=args.dry_run):
                print(artifact)
    except (EvidenceError, json.JSONDecodeError, OSError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
