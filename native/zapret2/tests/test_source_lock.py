"""Verify source integrity and fail closed when a vendored input changes."""

import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("nfqws2_builder", ROOT / "scripts/native/build-nfqws2.py")
BUILDER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUILDER)


class SourceLockTest(unittest.TestCase):
    def test_checked_sources_match_lock(self):
        manifest = BUILDER.verify_sources(ROOT / "native/zapret2")
        self.assertEqual(manifest["upstream"]["commit"], "ca950d838a0ee7dc32bb6ae5e65e54488c1bfe3b")

    def assert_tamper_rejected(self, name, expected):
        with tempfile.TemporaryDirectory(prefix="nfqws2-source-lock-") as directory:
            source = Path(directory) / "zapret2"
            shutil.copytree(ROOT / "native/zapret2", source)
            with (source / name).open("ab") as file:
                file.write(b"checksum test")
            with self.assertRaisesRegex(ValueError, expected):
                BUILDER.verify_sources(source)

    def test_dependency_archive_tamper_rejected(self):
        self.assert_tamper_rejected("archives/lua-5.4.8.tar.gz", "Dependency checksum mismatch")

    def test_upstream_source_tamper_rejected(self):
        self.assert_tamper_rejected("upstream/nfq2/darkmagic.c", "Upstream checksum mismatch")

    def test_integration_patch_tamper_rejected(self):
        self.assert_tamper_rejected("patches/android-vpn-protect.patch", "Integration checksum mismatch")

    def test_unlocked_extra_source_rejected(self):
        with tempfile.TemporaryDirectory(prefix="nfqws2-source-lock-") as directory:
            source = Path(directory) / "zapret2"
            shutil.copytree(ROOT / "native/zapret2", source)
            (source / "upstream/nfq2/unlocked.c").write_text("void unexpected(void) {}\n")
            with self.assertRaisesRegex(ValueError, "Upstream file inventory"):
                BUILDER.verify_sources(source)

    def test_unlocked_extra_bridge_rejected(self):
        with tempfile.TemporaryDirectory(prefix="nfqws2-source-lock-") as directory:
            source = Path(directory) / "zapret2"
            shutil.copytree(ROOT / "native/zapret2", source)
            (source / "bridge/ripdpi_protect.extra.c").write_text("void unexpected(void) {}\n")
            with self.assertRaisesRegex(ValueError, "Integration file inventory"):
                BUILDER.verify_sources(source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
