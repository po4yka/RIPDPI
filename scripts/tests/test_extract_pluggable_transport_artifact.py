from __future__ import annotations

import io
import subprocess
import tarfile
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
EXTRACTOR = ROOT / "scripts/ci/extract-pluggable-transport-artifact.sh"


def write_archive(archive: Path, member: tarfile.TarInfo) -> None:
    with tarfile.open(archive, "w:gz") as output:
        if member.isfile():
            payload = b"verified PT artifact"
            member.size = len(payload)
            output.addfile(member, io.BytesIO(payload))
        else:
            output.addfile(member)


def special_member(name: str, member_type: bytes, linkname: str = "") -> tarfile.TarInfo:
    member = tarfile.TarInfo(name)
    member.type = member_type
    member.linkname = linkname
    return member


class ExtractPluggableTransportArtifactTest(unittest.TestCase):
    def run_extractor(self, archive: Path, destination: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(EXTRACTOR), str(archive), str(destination)],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_extracts_regular_archive_and_replaces_stale_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "assets.tar.gz"
            destination = root / "destination"
            destination.mkdir()
            (destination / "stale").write_text("stale", encoding="utf-8")
            member = tarfile.TarInfo("metadata/pluggable-transports.json")
            write_archive(archive, member)

            result = self.run_extractor(archive, destination)

            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual("verified PT artifact", (destination / member.name).read_text())
            self.assertFalse((destination / "stale").exists())

    def test_rejects_unsafe_archive_members(self) -> None:
        unsafe_members = (
            tarfile.TarInfo("../escape"),
            tarfile.TarInfo("/absolute"),
            special_member("linked", tarfile.SYMTYPE, "target"),
            special_member("hard-linked", tarfile.LNKTYPE, "target"),
            special_member("device", tarfile.CHRTYPE),
            special_member("pipe", tarfile.FIFOTYPE),
        )
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for index, member in enumerate(unsafe_members):
                with self.subTest(member=member.name):
                    archive = root / f"unsafe-{index}.tar.gz"
                    write_archive(archive, member)
                    result = self.run_extractor(archive, root / f"destination-{index}")
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("unsafe pluggable transport artifact member", result.stderr)
