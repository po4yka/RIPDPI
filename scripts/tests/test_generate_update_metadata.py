from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci import generate_update_metadata


class GenerateUpdateMetadataTest(unittest.TestCase):
    def write_apk(self, directory: Path, name: str, size: int) -> Path:
        path = directory / name
        path.write_bytes(b"a" * size)
        return path

    def write_metadata(self, directory: Path) -> Path:
        metadata = {
            "elements": [
                {
                    "type": "ONE_OF_MANY",
                    "filters": [{"filterType": "ABI", "value": "arm64-v8a"}],
                    "outputFile": "app-github-arm64-v8a-release.apk",
                    "versionCode": 20000009,
                    "versionName": "0.1.1",
                },
                {
                    "type": "ONE_OF_MANY",
                    "filters": [{"filterType": "ABI", "value": "x86_64"}],
                    "outputFile": "app-github-x86_64-release.apk",
                    "versionCode": 40000009,
                    "versionName": "0.1.1",
                },
                {
                    "type": "UNIVERSAL",
                    "filters": [],
                    "outputFile": "app-github-universal-release.apk",
                    "versionCode": 9,
                    "versionName": "0.1.1",
                },
            ],
        }
        path = directory / "output-metadata.json"
        path.write_text(json.dumps(metadata), encoding="utf-8")
        return path

    def write_gradle_properties(self, directory: Path) -> Path:
        path = directory / "gradle.properties"
        path.write_text("ripdpi.minSdk=27\n", encoding="utf-8")
        return path

    def test_collect_release_apks_renames_outputs_to_stable_names(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.write_apk(root, "app-github-arm64-v8a-release.apk", 10)
            self.write_apk(root, "app-github-x86_64-release.apk", 20)
            self.write_apk(root, "app-github-universal-release.apk", 30)

            apks = generate_update_metadata.collect_release_apks(
                str(root / "*.apk"),
                self.write_metadata(root),
                "app-github-release",
            )

            self.assertEqual(
                [
                    "app-github-release-arm64-v8a.apk",
                    "app-github-release-x86_64.apk",
                    "app-github-release-universal.apk",
                ],
                [apk.path.name for apk in apks],
            )
            self.assertTrue((root / "app-github-release-arm64-v8a.apk").is_file())
            self.assertFalse((root / "app-github-arm64-v8a-release.apk").exists())

    def test_write_update_metadata_keeps_universal_v1_fields_and_v2_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.write_apk(root, "app-github-arm64-v8a-release.apk", 10)
            self.write_apk(root, "app-github-x86_64-release.apk", 20)
            self.write_apk(root, "app-github-universal-release.apk", 30)
            apks = generate_update_metadata.collect_release_apks(
                str(root / "*.apk"),
                self.write_metadata(root),
                "app-github-release",
            )
            output = root / "update.json"

            generate_update_metadata.write_update_metadata(
                output,
                apks,
                package_name="com.poyka.ripdpi",
                min_sdk=27,
                changelog="Release notes",
            )
            payload = json.loads(output.read_text(encoding="utf-8"))

            self.assertEqual(2, payload["schemaVersion"])
            self.assertEqual("app-github-release-universal.apk", payload["apkName"])
            self.assertEqual(9, payload["versionCode"])
            self.assertEqual(["arm64-v8a", "x86_64", "universal"], [item["abi"] for item in payload["artifacts"]])

    def test_size_budget_rejects_oversized_abi_apk_but_ignores_universal(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            self.write_apk(root, "app-github-arm64-v8a-release.apk", 100)
            self.write_apk(root, "app-github-x86_64-release.apk", 20)
            self.write_apk(root, "app-github-universal-release.apk", 10_000)
            apks = generate_update_metadata.collect_release_apks(
                str(root / "*.apk"),
                self.write_metadata(root),
                "app-github-release",
            )

            with self.assertRaisesRegex(SystemExit, "size budget exceeded"):
                generate_update_metadata.verify_size_budget(apks, max_abi_size_bytes=90)


if __name__ == "__main__":
    unittest.main()
