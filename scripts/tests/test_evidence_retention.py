#!/usr/bin/env python3

from __future__ import annotations

import io
import json
import subprocess
import struct
import tarfile
import tempfile
import unittest
from datetime import UTC, datetime
from pathlib import Path

from scripts.ci.evidence_retention import (
    EvidenceError,
    check_archive,
    create_manifest,
    purge_expired,
    validate_policy,
)


ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "quality/evidence-retention.json"


def write_archive(path: Path, files: dict[str, bytes]) -> None:
    with tarfile.open(path, "w:gz") as archive:
        for name, payload in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            archive.addfile(info, io.BytesIO(payload))


def classic_pcap() -> bytes:
    return b"\xd4\xc3\xb2\xa1" + struct.pack("<HHiiII", 2, 4, 0, 0, 65535, 1)


class EvidenceRetentionTest(unittest.TestCase):
    def test_archive_defaults_public_and_requires_explicit_private_pcap_class(self) -> None:
        archive = (ROOT / "test-lab/scripts/archive-artifacts.sh").read_text(encoding="utf-8")
        purge = (ROOT / "test-lab/scripts/purge-evidence.sh").read_text(encoding="utf-8")
        ci = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertIn('retention_class="public-sanitized"', archive)
        self.assertIn('--retention-class private-raw-pcap', archive)
        self.assertIn('"$retention_class" == "private-raw-pcap"', archive)
        self.assertIn("evidence_retention.py", archive)
        self.assertIn("evidence_retention.py", purge)
        self.assertIn("scripts.tests.test_evidence_retention", ci)

    def test_checked_in_policy_defines_bounded_public_private_and_transient_classes(self) -> None:
        policy = validate_policy(POLICY)
        self.assertEqual(
            {"public-sanitized", "private-raw-pcap", "transient-candidate-download"},
            set(policy["classes"]),
        )
        self.assertFalse(policy["classes"]["public-sanitized"]["allowRawPcap"])
        self.assertLessEqual(policy["classes"]["private-raw-pcap"]["maxAgeHours"], 168)
        self.assertLessEqual(
            policy["classes"]["transient-candidate-download"]["maxAgeHours"], 24
        )

    def test_public_archive_rejects_pcap_and_binary_secret_material(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            pcap = root / "pcap.tar.gz"
            write_archive(pcap, {"bundle/capture/raw.pcap": classic_pcap()})
            with self.assertRaisesRegex(EvidenceError, "raw packet capture"):
                check_archive(pcap, POLICY, "public-sanitized")

            binary = root / "binary.tar.gz"
            write_archive(binary, {"bundle/data.bin": b"prefix\0private_key=abc123\0suffix"})
            with self.assertRaisesRegex(EvidenceError, "sensitive binary payload"):
                check_archive(binary, POLICY, "public-sanitized")

            disguised = root / "disguised.tar.gz"
            write_archive(disguised, {"bundle/capture/raw.bin": classic_pcap()})
            with self.assertRaisesRegex(EvidenceError, "raw packet capture"):
                check_archive(disguised, POLICY, "public-sanitized")

            quoted = root / "quoted.tar.gz"
            write_archive(quoted, {"bundle/report.json": b'{"token":"abc123"}'})
            with self.assertRaisesRegex(EvidenceError, "sensitive binary payload"):
                check_archive(quoted, POLICY, "public-sanitized")

            sensitive_name = root / "sensitive-name.tar.gz"
            write_archive(sensitive_name, {"bundle/token=abc123.txt": b"safe"})
            with self.assertRaisesRegex(EvidenceError, "sensitive binary payload"):
                check_archive(sensitive_name, POLICY, "public-sanitized")

            ssid = root / "ssid.tar.gz"
            write_archive(ssid, {"bundle/report.json": b'{"ssid":"home"}'})
            with self.assertRaisesRegex(EvidenceError, "sensitive binary payload"):
                check_archive(ssid, POLICY, "public-sanitized")

            for index, payload in enumerate((
                b"token=<redacted>abc123",
                b"token=nullabc123",
                b"token=falseSecret",
            )):
                prefixed = root / f"prefixed-{index}.tar.gz"
                write_archive(prefixed, {"bundle/report.txt": payload})
                with self.subTest(payload=payload), self.assertRaisesRegex(
                    EvidenceError, "sensitive binary payload"
                ):
                    check_archive(prefixed, POLICY, "public-sanitized")

            safe = root / "safe-values.tar.gz"
            safe_payload = b"\n".join(
                f"token={value}".encode()
                for value in ("<redacted>", "null", "false", "true", "0", "[]", "{}")
            ) + b'\n{"token":"{}"}'
            write_archive(safe, {"bundle/report.txt": safe_payload})
            check_archive(safe, POLICY, "public-sanitized")

    def test_archive_rejects_unsafe_members_and_resource_exhaustion(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            for name in ("../escape", "/absolute"):
                archive = root / (name.replace("/", "_") + ".tar.gz")
                write_archive(archive, {name: b"payload"})
                with self.assertRaisesRegex(EvidenceError, "unsafe archive member"):
                    check_archive(archive, POLICY, "public-sanitized")
            symlink = root / "symlink.tar.gz"
            with tarfile.open(symlink, "w:gz") as archive:
                info = tarfile.TarInfo("bundle/link")
                info.type = tarfile.SYMTYPE
                info.linkname = "../../escape"
                archive.addfile(info)
            with self.assertRaisesRegex(EvidenceError, "unsafe archive member"):
                check_archive(symlink, POLICY, "public-sanitized")

            oversized = root / "oversized.tar.gz"
            write_archive(oversized, {"bundle/large.bin": b"x" * (17 * 1024 * 1024)})
            with self.assertRaisesRegex(EvidenceError, "member size limit"):
                check_archive(oversized, POLICY, "public-sanitized")

    def test_private_pcap_requires_valid_header_and_expiring_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "private.tar.gz"
            write_archive(archive, {"bundle/capture/raw.pcap": classic_pcap()})
            check_archive(archive, POLICY, "private-raw-pcap")
            manifest = create_manifest(
                POLICY,
                "private-raw-pcap",
                archive,
                datetime(2026, 8, 4, tzinfo=UTC),
            )
            self.assertTrue(manifest["containsRawPcap"])
            self.assertEqual("2026-08-11T00:00:00Z", manifest["expiresUtc"])

            invalid = root / "invalid.tar.gz"
            write_archive(invalid, {"bundle/capture/raw.pcap": b"not-a-pcap"})
            with self.assertRaisesRegex(EvidenceError, "invalid PCAP"):
                check_archive(invalid, POLICY, "private-raw-pcap")

            corrupt = root / "corrupt.tar.gz"
            write_archive(
                corrupt,
                {"bundle/capture/raw.pcap": b"\xd4\xc3\xb2\xa1" + struct.pack(
                    "<HHiiII", 0, 0, 0, 0, 0, 1
                )},
            )
            with self.assertRaisesRegex(EvidenceError, "invalid PCAP"):
                check_archive(corrupt, POLICY, "private-raw-pcap")

    def test_purge_deletes_only_expired_managed_evidence_under_exact_root(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            expired = root / "expired.tar.gz"
            fresh = root / "fresh.tar.gz"
            unmanaged = root / "unmanaged.tar.gz"
            for path in (expired, fresh, unmanaged):
                write_archive(path, {"bundle/report.txt": b"safe"})
            expired_manifest = create_manifest(
                POLICY, "public-sanitized", expired,
                datetime(2026, 7, 1, tzinfo=UTC),
            )
            (root / "expired.tar.gz.retention.json").write_text(
                json.dumps(expired_manifest),
                encoding="utf-8",
            )
            fresh_manifest = create_manifest(
                POLICY, "public-sanitized", fresh,
                datetime(2026, 8, 1, tzinfo=UTC),
            )
            (root / "fresh.tar.gz.retention.json").write_text(
                json.dumps(fresh_manifest),
                encoding="utf-8",
            )
            removed = purge_expired(root, datetime(2026, 8, 4, tzinfo=UTC), dry_run=False)
            self.assertEqual([expired], removed)
            self.assertFalse(expired.exists())
            self.assertTrue(fresh.exists())
            self.assertTrue(unmanaged.exists())

    def test_purge_rejects_forged_or_stale_sidecars(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            artifact = root / "managed.tar.gz"
            write_archive(artifact, {"bundle/report.txt": b"safe"})
            manifest = create_manifest(
                POLICY, "public-sanitized", artifact,
                datetime(2026, 7, 1, tzinfo=UTC),
            )
            sidecar = root / "managed.tar.gz.retention.json"
            for field, value, message in (
                ("sha256", "0" * 64, "digest"),
                ("retentionClass", "private-raw-pcap", "localOnly"),
                ("expiresUtc", "2026-08-31T00:00:00Z", "retention"),
            ):
                forged = dict(manifest)
                forged[field] = value
                sidecar.write_text(json.dumps(forged), encoding="utf-8")
                with self.subTest(field=field), self.assertRaisesRegex(EvidenceError, message):
                    purge_expired(root, datetime(2026, 9, 1, tzinfo=UTC), dry_run=False)
                self.assertTrue(artifact.exists())

    def test_shell_scanner_validates_archive_before_any_extraction(self) -> None:
        source = (ROOT / "test-lab/scripts/check-artifact-redaction.sh").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("tar -x", source)

    def test_destructive_cli_rejects_root_outside_checked_in_policy(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            result = subprocess.run(
                [
                    "python3", str(ROOT / "scripts/ci/evidence_retention.py"),
                    "--policy", str(POLICY), "purge", raw,
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("not declared by policy", result.stderr)

    def test_policy_purge_root_rejects_symlink_redirect(self) -> None:
        managed = ROOT / "build/release-candidate-downloads"
        if managed.exists():
            self.skipTest("managed root already exists")
        with tempfile.TemporaryDirectory() as raw:
            managed.parent.mkdir(parents=True, exist_ok=True)
            managed.symlink_to(Path(raw), target_is_directory=True)
            try:
                result = subprocess.run(
                    [
                        "python3", str(ROOT / "scripts/ci/evidence_retention.py"),
                        "--policy", str(POLICY), "purge", str(managed),
                    ],
                    cwd=ROOT,
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertNotEqual(0, result.returncode)
                self.assertIn("contains a symlink", result.stderr)
            finally:
                managed.unlink()

    def test_archive_rejects_custom_unmanaged_output(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            result = subprocess.run(
                [
                    "bash", str(ROOT / "test-lab/scripts/archive-artifacts.sh"),
                    "--output", raw,
                ],
                cwd=ROOT,
                text=True,
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("policy-managed", result.stderr)

    def test_private_archive_removes_source_captures_after_verified_manifest(self) -> None:
        source = (ROOT / "test-lab/scripts/archive-artifacts.sh").read_text(encoding="utf-8")
        self.assertIn("raw_capture_sources", source)
        self.assertLess(source.index("write-manifest"), source.index('rm -f -- "${raw_capture_sources[@]}"'))
        self.assertIn('published=false', source)
        self.assertIn('reservation="$(mktemp', source)
        self.assertIn('staged_archive="$work_dir/$archive_name"', source)
        self.assertLess(source.index('write-manifest'), source.index('mv -- "$staged_archive" "$archive_path"'))
        self.assertIn('rm -f -- "$archive_path" "$archive_path.retention.json"', source)
        self.assertLess(source.index("write-manifest"), source.index("published=true"))

    def test_transient_download_helper_cleans_its_managed_directory(self) -> None:
        helper = ROOT / "scripts/ci/with-transient-release-downloads.sh"
        source = helper.read_text(encoding="utf-8")
        self.assertIn("mktemp -d", source)
        self.assertIn("trap cleanup EXIT", source)
        self.assertIn("RIPDPI_RELEASE_DOWNLOAD_DIR", source)


if __name__ == "__main__":
    unittest.main()
