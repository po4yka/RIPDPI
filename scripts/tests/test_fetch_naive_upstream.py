from __future__ import annotations

import hashlib
import importlib.util
import json
import stat
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("fetch_naive_upstream", ROOT / "scripts/ci/fetch-naive-upstream.py")
assert SPEC is not None and SPEC.loader is not None
fetch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fetch)

ELF = b"\x7fELF-fixture-naive"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class FetchNaiveUpstreamTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.out = self.tmp / "artifacts"
        self.downloads = 0

    def make_apk(self, members: dict[str, bytes], symlink: str | None = None) -> bytes:
        path = self.tmp / "fixture.apk"
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in members.items():
                archive.writestr(name, data)
            if symlink:
                info = zipfile.ZipInfo(symlink)
                info.external_attr = (stat.S_IFLNK | 0o777) << 16
                archive.writestr(info, "target")
        return path.read_bytes()

    def run_main(self, apk: bytes, elf_sha: str | None = None, apk_sha: str | None = None) -> tuple[int, str]:
        manifest = {
            "schemaVersion": 1,
            "abis": {
                "x86": {
                    "asset": "fixture.apk",
                    "url": "https://example.invalid/fixture.apk",
                    "apkSha256": apk_sha or sha(apk),
                    "libnaiveSha256": elf_sha or sha(ELF),
                }
            },
        }
        manifest_path = self.tmp / "sources.json"
        manifest_path.write_text(json.dumps(manifest))

        def fake_download(url: str, target: Path) -> None:
            self.downloads += 1
            target.write_bytes(apk)

        err = StringIO()
        with mock.patch.object(fetch, "download", fake_download), redirect_stderr(err):
            code = fetch.main(["--abi", "x86", "--output-root", str(self.out), "--manifest", str(manifest_path)])
        return code, err.getvalue()

    def test_happy_path_extracts_verified_executable(self) -> None:
        code, _ = self.run_main(self.make_apk({"lib/x86/libnaive.so": ELF, "classes.dex": b"junk"}))
        target = self.out / "x86" / "libnaive.so"
        self.assertEqual(0, code)
        self.assertEqual(ELF, target.read_bytes())
        self.assertEqual(0o755, stat.S_IMODE(target.stat().st_mode))
        self.assertEqual(["libnaive.so"], [p.name for p in (self.out / "x86").iterdir()])

    def test_second_run_skips_download(self) -> None:
        apk = self.make_apk({"lib/x86/libnaive.so": ELF})
        self.assertEqual(0, self.run_main(apk)[0])
        self.assertEqual(0, self.run_main(apk)[0])
        self.assertEqual(1, self.downloads)

    def test_corrupt_cached_file_is_refetched(self) -> None:
        apk = self.make_apk({"lib/x86/libnaive.so": ELF})
        self.assertEqual(0, self.run_main(apk)[0])
        (self.out / "x86" / "libnaive.so").write_bytes(b"tampered")
        self.assertEqual(0, self.run_main(apk)[0])
        self.assertEqual(2, self.downloads)
        self.assertEqual(ELF, (self.out / "x86" / "libnaive.so").read_bytes())

    def test_apk_digest_mismatch_fails(self) -> None:
        code, err = self.run_main(self.make_apk({"lib/x86/libnaive.so": ELF}), apk_sha="0" * 64)
        self.assertEqual(1, code)
        self.assertIn("APK sha256 mismatch", err)
        self.assertFalse((self.out / "x86" / "libnaive.so").exists())

    def test_elf_digest_mismatch_fails(self) -> None:
        code, err = self.run_main(self.make_apk({"lib/x86/libnaive.so": ELF}), elf_sha="1" * 64)
        self.assertEqual(1, code)
        self.assertIn("libnaive.so sha256 mismatch", err)
        self.assertFalse((self.out / "x86" / "libnaive.so").exists())

    def test_missing_member_fails(self) -> None:
        code, err = self.run_main(self.make_apk({"lib/arm64-v8a/libnaive.so": ELF}))
        self.assertEqual(1, code)
        self.assertIn("no member lib/x86/libnaive.so", err)

    def test_zip_slip_member_fails(self) -> None:
        for name in ("../evil", "/abs/evil", "lib/../../evil"):
            with self.subTest(name=name):
                code, err = self.run_main(self.make_apk({"lib/x86/libnaive.so": ELF, name: b"x"}))
                self.assertEqual(1, code)
                self.assertIn("unsafe member path", err)
                self.assertFalse((self.tmp / "evil").exists())

    def test_symlink_member_fails(self) -> None:
        code, err = self.run_main(self.make_apk({"lib/x86/libnaive.so": ELF}, symlink="lib/x86/link"))
        self.assertEqual(1, code)
        self.assertIn("symlink member", err)

    def test_committed_manifest_covers_all_abis(self) -> None:
        manifest = json.loads((ROOT / "native/naiveproxy-upstream/sources.json").read_text())
        self.assertEqual({"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}, set(manifest["abis"]))
        for entry in manifest["abis"].values():
            self.assertRegex(entry["apkSha256"], r"^[0-9a-f]{64}$")
            self.assertRegex(entry["libnaiveSha256"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
