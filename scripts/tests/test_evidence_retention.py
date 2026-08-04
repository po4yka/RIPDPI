#!/usr/bin/env python3

from __future__ import annotations

import io
import json
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
            write_archive(pcap, {"bundle/capture/raw.pcap": b"\xd4\xc3\xb2\xa1" + b"\0" * 20})
            with self.assertRaisesRegex(EvidenceError, "raw packet capture"):
                check_archive(pcap, POLICY, "public-sanitized")

            binary = root / "binary.tar.gz"
            write_archive(binary, {"bundle/data.bin": b"prefix\0private_key=abc123\0suffix"})
            with self.assertRaisesRegex(EvidenceError, "sensitive binary payload"):
                check_archive(binary, POLICY, "public-sanitized")

    def test_private_pcap_requires_valid_header_and_expiring_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            archive = root / "private.tar.gz"
            write_archive(archive, {"bundle/capture/raw.pcap": b"\xd4\xc3\xb2\xa1" + b"\0" * 20})
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

    def test_purge_deletes_only_expired_managed_evidence_under_exact_root(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw).resolve()
            expired = root / "expired.tar.gz"
            fresh = root / "fresh.tar.gz"
            unmanaged = root / "unmanaged.tar.gz"
            for path in (expired, fresh, unmanaged):
                path.write_bytes(b"artifact")
            (root / "expired.tar.gz.retention.json").write_text(
                json.dumps(
                    {
                        "version": "ripdpi_evidence_retention_v1",
                        "artifact": expired.name,
                        "expiresUtc": "2026-08-03T00:00:00Z",
                    }
                ),
                encoding="utf-8",
            )
            (root / "fresh.tar.gz.retention.json").write_text(
                json.dumps(
                    {
                        "version": "ripdpi_evidence_retention_v1",
                        "artifact": fresh.name,
                        "expiresUtc": "2026-08-05T00:00:00Z",
                    }
                ),
                encoding="utf-8",
            )
            removed = purge_expired(root, datetime(2026, 8, 4, tzinfo=UTC), dry_run=False)
            self.assertEqual([expired], removed)
            self.assertFalse(expired.exists())
            self.assertTrue(fresh.exists())
            self.assertTrue(unmanaged.exists())


if __name__ == "__main__":
    unittest.main()
