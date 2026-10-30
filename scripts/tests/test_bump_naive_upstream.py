from __future__ import annotations

import hashlib
import importlib.util
import json
import re
import shutil
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("bump_naive_upstream", ROOT / "scripts/ci/bump-naive-upstream.py")
assert SPEC is not None and SPEC.loader is not None
bump = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(bump)

NEW_TAG = "v155.0.8100.12-1"


class BumpNaiveUpstreamTest(unittest.TestCase):
    def setUp(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.tmp = Path(tmp.name)
        self.manifest = self.tmp / "sources.json"
        shutil.copy(ROOT / "native/naiveproxy-upstream/sources.json", self.manifest)
        self.kotlin = self.tmp / "NaiveUpstreamLauncher.kt"
        self.kotlin.write_text('internal const val NaiveUpstreamVersionPrefix = "naive 154.0.8037.49"\n', encoding="utf-8")
        self.apks: dict[str, bytes] = {}
        self.elfs: dict[str, bytes] = {}
        for abi in bump.ABIS:
            self.elfs[abi] = f"\x7fELF-{abi}".encode()
            path = self.tmp / f"{abi}.apk"
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr(f"lib/{abi}/libnaive.so", self.elfs[abi])
            self.apks[f"https://example.test/{abi}.apk"] = path.read_bytes()

    def release(self, tag: str, corrupt: str | None = None) -> dict:
        assets = []
        for abi in bump.ABIS:
            url = f"https://example.test/{abi}.apk"
            digest = hashlib.sha256(self.apks[url]).hexdigest()
            if abi == corrupt:
                digest = "0" * 64
            assets.append({"name": f"naiveproxy-plugin-{tag}-{abi}.apk", "browser_download_url": url, "digest": f"sha256:{digest}"})
        return {"tag_name": tag, "assets": assets}

    def api(self, release: dict):
        def get(path: str) -> object:
            if path.endswith("/releases/latest"):
                return release
            if path.endswith(f"/git/ref/tags/{release['tag_name']}"):
                return {"object": {"type": "tag", "sha": "annotated"}}
            if path.endswith("/git/tags/annotated"):
                return {"object": {"type": "commit", "sha": "c0ffee"}}
            raise AssertionError(path)

        return get

    def run_bump(self, release: dict, *extra: str) -> int:
        def download(url: str, target: Path) -> None:
            target.write_bytes(self.apks[url])

        args = ["--manifest", str(self.manifest), "--kotlin-source", str(self.kotlin), *extra]
        with mock.patch.object(bump.fetch, "download", download), redirect_stdout(StringIO()):
            return bump.main(args, get=self.api(release))

    def test_check_reports_a_newer_release_and_accepts_the_pinned_one(self) -> None:
        self.assertEqual(2, self.run_bump(self.release(NEW_TAG), "--check"))
        self.assertEqual(0, self.run_bump(self.release("v154.0.8037.49-5"), "--check"))
        self.assertEqual(0, self.run_bump(self.release("v154.0.8037.49-4"), "--check"))

    def test_bump_pins_digests_commit_and_version_check(self) -> None:
        self.assertEqual(0, self.run_bump(self.release(NEW_TAG)))

        manifest = json.loads(self.manifest.read_text(encoding="utf-8"))
        self.assertEqual((NEW_TAG, "c0ffee", 1), (manifest["tag"], manifest["commit"], manifest["schemaVersion"]))
        arm64 = manifest["abis"]["arm64-v8a"]
        self.assertEqual(hashlib.sha256(self.elfs["arm64-v8a"]).hexdigest(), arm64["libnaiveSha256"])
        self.assertEqual(f"naiveproxy-plugin-{NEW_TAG}-arm64-v8a.apk", arm64["asset"])
        self.assertIn('"naive 155.0.8100.12"', self.kotlin.read_text(encoding="utf-8"))

    def test_digest_mismatch_changes_nothing(self) -> None:
        before = self.manifest.read_text(encoding="utf-8")

        self.assertEqual(1, self.run_bump(self.release(NEW_TAG, corrupt="x86")))

        self.assertEqual(before, self.manifest.read_text(encoding="utf-8"))
        self.assertIn('"naive 154.0.8037.49"', self.kotlin.read_text(encoding="utf-8"))

    def test_kotlin_version_check_matches_the_pinned_tag(self) -> None:
        tag = json.loads((ROOT / "native/naiveproxy-upstream/sources.json").read_text(encoding="utf-8"))["tag"]
        source = bump.DEFAULT_KOTLIN.read_text(encoding="utf-8")

        match = re.search(r'NaiveUpstreamVersionPrefix = "([^"]+)"', source)

        self.assertIsNotNone(match)
        self.assertEqual(bump.version_prefix(tag), match.group(1) if match else None)


if __name__ == "__main__":
    unittest.main()
