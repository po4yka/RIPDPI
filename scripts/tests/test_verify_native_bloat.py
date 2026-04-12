from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ci import verify_native_bloat


class VerifyNativeBloatTest(unittest.TestCase):
    def test_parse_bloat_output_reads_last_json_line(self) -> None:
        payload = verify_native_bloat.parse_bloat_output(
            "\n".join(
                [
                    "Compiling ripdpi-android v0.1.0",
                    "Finished `android-jni` profile [optimized] target(s) in 1.23s",
                    '{"file-size":1234,"text-section-size":567,"functions":[]}',
                ],
            ),
        )

        self.assertEqual(1234, payload["file-size"])
        self.assertEqual(567, payload["text-section-size"])

    def test_compare_named_items_flags_growth_and_large_new_entries(self) -> None:
        failures = verify_native_bloat.compare_named_items(
            "ripdpi-android",
            "functions",
            baseline_items=[{"name": "foo", "size": 1000}],
            current_items=[{"name": "foo", "size": 7000}, {"name": "bar", "size": 13000}],
            max_growth_bytes=1024,
            max_new_item_size_bytes=12000,
        )

        self.assertEqual(2, len(failures))
        self.assertIn("foo", failures[0])
        self.assertIn("bar", failures[1])

    def test_resolve_sdk_dir_falls_back_to_local_properties(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            (repo_root / "local.properties").write_text("sdk.dir=/tmp/android-sdk\n", encoding="utf-8")

            with patch.dict("os.environ", {}, clear=True):
                resolved = verify_native_bloat.resolve_sdk_dir(repo_root)

            self.assertEqual(Path("/tmp/android-sdk").resolve(), resolved)

    def test_diff_named_items_tracks_growth_and_new_entries(self) -> None:
        diff = verify_native_bloat.diff_named_items(
            baseline_items=[{"name": "foo", "size": 1000, "crate": "a"}],
            current_items=[
                {"name": "foo", "size": 1600, "crate": "a"},
                {"name": "bar", "size": 700, "crate": "b"},
            ],
        )

        self.assertEqual("bar", diff[0]["name"])
        self.assertEqual(700, diff[0]["deltaBytes"])
        self.assertEqual("foo", diff[1]["name"])
        self.assertEqual(600, diff[1]["deltaBytes"])

    def test_render_bloat_report_markdown_mentions_likely_drivers(self) -> None:
        markdown = verify_native_bloat.render_bloat_report_markdown(
            {
                "target": "x86_64-linux-android",
                "profile": "android-jni",
                "packages": {
                    "ripdpi-android": {
                        "fileSize": {"baseline": 1000, "current": 1200, "deltaBytes": 200},
                        "textSection": {
                            "baseline": 400,
                            "current": 450,
                            "deltaBytes": 50,
                            "allowed": 500,
                            "status": "ok",
                        },
                        "topCrateGrowth": [
                            {
                                "name": "ripdpi_runtime",
                                "crate": None,
                                "baselineSize": 100,
                                "currentSize": 140,
                                "deltaBytes": 40,
                                "status": "changed",
                            },
                        ],
                        "topFunctionGrowth": [
                            {
                                "name": "send_with_group",
                                "crate": "ripdpi_runtime",
                                "baselineSize": 20,
                                "currentSize": 30,
                                "deltaBytes": 10,
                                "status": "changed",
                            },
                        ],
                        "likelyDrivers": ["ripdpi_runtime"],
                    },
                },
                "failures": [],
            },
        )

        self.assertIn("# Native Bloat Attribution Report", markdown)
        self.assertIn("likely crate drivers", markdown)
        self.assertIn("ripdpi_runtime", markdown)


if __name__ == "__main__":
    unittest.main()
