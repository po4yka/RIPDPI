from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "sync_host_packs.py"
SPEC = importlib.util.spec_from_file_location("sync_host_packs", SCRIPT_PATH)
sync_host_packs = importlib.util.module_from_spec(SPEC)
assert SPEC is not None and SPEC.loader is not None
sys.modules[SPEC.name] = sync_host_packs
SPEC.loader.exec_module(sync_host_packs)


class SyncHostPacksTest(unittest.TestCase):
    def test_normalize_source_payload_supports_geosite_and_plain_tokens(self) -> None:
        payload = """
        # comment
        domain:YouTube.com
        full:YT3.googleusercontent.com
        keyword:skipme
        regexp:skipme
        t.me
        bad^host
        youtube.com
        """

        hosts = sync_host_packs.normalize_source_payload(payload)

        self.assertEqual(
            ["youtube.com", "yt3.googleusercontent.com", "t.me"],
            hosts,
        )

    def test_build_catalog_records_source_metadata(self) -> None:
        manifest = {
            "version": 1,
            "packs": [
                {
                    "id": "telegram",
                    "title": "Telegram",
                    "description": "Messenger hosts",
                    "source": {
                        "name": "v2fly/domain-list-community",
                        "repo": "v2fly/domain-list-community",
                        "ref": "master",
                        "path": "data/telegram",
                        "rawUrl": "https://example.test/telegram",
                    },
                }
            ],
        }

        with (
            mock.patch.object(sync_host_packs, "http_get_text", return_value="telegram.org\nt.me\n"),
            mock.patch.object(sync_host_packs, "fetch_latest_commit", return_value="abc123def456"),
        ):
            catalog = sync_host_packs.build_catalog(manifest)

        self.assertEqual(1, catalog["version"])
        self.assertEqual(1, len(catalog["packs"]))
        self.assertEqual(["telegram.org", "t.me"], catalog["packs"][0]["hosts"])
        self.assertEqual("abc123def456", catalog["packs"][0]["sources"][0]["commit"])

    def test_write_catalog_check_mode_detects_stale_output(self) -> None:
        catalog = {"version": 1, "generatedAt": "2026-03-12T00:00:00Z", "packs": []}
        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "catalog.json"
            output_path.write_text("{\"version\":0}\n", encoding="utf-8")

            result = sync_host_packs.write_catalog(catalog, output_path, check_only=True)

        self.assertEqual(1, result)


if __name__ == "__main__":
    unittest.main()
