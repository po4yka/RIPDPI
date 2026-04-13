from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "sync_app_routing.py"
SPEC = importlib.util.spec_from_file_location("sync_app_routing", SCRIPT_PATH)
sync_app_routing = importlib.util.module_from_spec(SPEC)
assert SPEC is not None and SPEC.loader is not None
sys.modules[SPEC.name] = sync_app_routing
SPEC.loader.exec_module(sync_app_routing)


class NormalizePackageNameTest(unittest.TestCase):
    def test_accepts_valid_packages(self) -> None:
        self.assertEqual("com.example.app", sync_app_routing.normalize_package_name("com.example.app"))
        self.assertEqual("ru.yandex.music", sync_app_routing.normalize_package_name("ru.yandex.music"))
        self.assertEqual("com.vkontakte.android", sync_app_routing.normalize_package_name("com.vkontakte.android"))

    def test_rejects_bare_label(self) -> None:
        self.assertIsNone(sync_app_routing.normalize_package_name("justlabel"))

    def test_rejects_path(self) -> None:
        self.assertIsNone(sync_app_routing.normalize_package_name("/path/to/file"))

    def test_rejects_spaces(self) -> None:
        self.assertIsNone(sync_app_routing.normalize_package_name("has spaces.in.name"))

    def test_rejects_empty(self) -> None:
        self.assertIsNone(sync_app_routing.normalize_package_name(""))
        self.assertIsNone(sync_app_routing.normalize_package_name("   "))

    def test_trims_whitespace(self) -> None:
        self.assertEqual("com.example.app", sync_app_routing.normalize_package_name("  com.example.app  "))


class BuildPackageListTest(unittest.TestCase):
    def test_records_source_metadata(self) -> None:
        manifest = {
            "version": 1,
            "sources": [
                {
                    "name": "test-source",
                    "repo": "test/repo",
                    "ref": "main",
                    "path": "apps.json",
                    "rawUrl": "https://example.test/apps.json",
                },
            ],
        }

        source_json = json.dumps(["com.example.app", "ru.test.app", "invalid!", "ru.another.app"])

        with (
            mock.patch.object(sync_app_routing, "http_get_text", return_value=source_json),
            mock.patch.object(sync_app_routing, "fetch_latest_commit", return_value="abc123"),
        ):
            packages, sources_meta = sync_app_routing.build_package_list(manifest)

        self.assertEqual(["com.example.app", "ru.another.app", "ru.test.app"], packages)
        self.assertEqual(1, len(sources_meta))
        self.assertEqual("abc123", sources_meta[0]["commit"])
        self.assertEqual("test-source", sources_meta[0]["name"])

    def test_deduplicates_packages(self) -> None:
        manifest = {
            "version": 1,
            "sources": [
                {
                    "name": "src",
                    "repo": "t/r",
                    "ref": "main",
                    "path": "a.json",
                    "rawUrl": "https://example.test/a.json",
                },
            ],
        }

        source_json = json.dumps(["com.example.app", "com.example.app", "ru.test.app"])

        with (
            mock.patch.object(sync_app_routing, "http_get_text", return_value=source_json),
            mock.patch.object(sync_app_routing, "fetch_latest_commit", return_value="def456"),
        ):
            packages, _ = sync_app_routing.build_package_list(manifest)

        self.assertEqual(["com.example.app", "ru.test.app"], packages)


class WritePolicyCheckModeTest(unittest.TestCase):
    def test_detects_stale_packages(self) -> None:
        policy = {
            "presets": [
                {
                    "id": "russian-mainstream",
                    "exactPackages": ["com.new.app", "ru.new.app"],
                }
            ]
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "policy.json"
            existing = {
                "presets": [
                    {
                        "id": "russian-mainstream",
                        "exactPackages": ["com.old.app"],
                    }
                ]
            }
            output_path.write_text(json.dumps(existing), encoding="utf-8")

            result = sync_app_routing.write_policy(policy, output_path, check_only=True)

        self.assertEqual(1, result)

    def test_returns_zero_when_current(self) -> None:
        packages = ["com.example.app", "ru.test.app"]
        policy = {
            "presets": [
                {
                    "id": "russian-mainstream",
                    "exactPackages": packages,
                }
            ]
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            output_path = Path(temp_dir) / "policy.json"
            output_path.write_text(json.dumps(policy), encoding="utf-8")

            result = sync_app_routing.write_policy(policy, output_path, check_only=True)

        self.assertEqual(0, result)


if __name__ == "__main__":
    unittest.main()
