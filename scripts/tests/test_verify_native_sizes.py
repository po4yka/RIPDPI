from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from scripts.ci import verify_native_sizes


class VerifyNativeSizesTest(unittest.TestCase):
    def write_libraries(self, lib_dir: Path, sizes: dict[str, dict[str, int]]) -> None:
        for abi, libraries in sizes.items():
            abi_dir = lib_dir / abi
            abi_dir.mkdir(parents=True, exist_ok=True)
            for library, size in libraries.items():
                (abi_dir / library).write_bytes(b"\0" * size)

    def test_collect_sizes_only_includes_tracked_libraries(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib_dir = Path(temp_dir)
            (lib_dir / "arm64-v8a").mkdir()
            (lib_dir / "arm64-v8a" / "libripdpi.so").write_bytes(b"\0" * 10)
            (lib_dir / "arm64-v8a" / "libother.so").write_bytes(b"\0" * 99)

            sizes = verify_native_sizes.collect_sizes(lib_dir)

            self.assertEqual({"arm64-v8a": {"libripdpi.so": 10}}, sizes)

    def test_verify_enforces_per_library_and_total_thresholds(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib_dir = Path(temp_dir)
            self.write_libraries(
                lib_dir,
                {
                    "arm64-v8a": {
                        "libripdpi.so": 110,
                        "libripdpi-tunnel.so": 205,
                    },
                },
            )
            baseline = {
                "maxPerLibraryGrowthBytes": 8,
                "maxTotalGrowthPercent": 2,
                "maxTotalGrowthBytes": 10,
                "libraries": {
                    "arm64-v8a": {
                        "libripdpi.so": 100,
                        "libripdpi-tunnel.so": 200,
                    },
                },
            }

            with self.assertRaisesRegex(ValueError, "libripdpi.so size regression"):
                verify_native_sizes.verify(lib_dir, baseline)

    def test_build_baseline_payload_matches_expected_shape(self) -> None:
        payload = json.loads(
            verify_native_sizes.build_baseline_payload(
                {"x86_64": {"libripdpi.so": 1234}},
                max_per_library_growth_bytes=131072,
                max_total_growth_percent=2.0,
                max_total_growth_bytes=262144,
            ),
        )

        self.assertEqual(131072, payload["maxPerLibraryGrowthBytes"])
        self.assertEqual(2.0, payload["maxTotalGrowthPercent"])
        self.assertEqual(262144, payload["maxTotalGrowthBytes"])
        self.assertEqual(1234, payload["libraries"]["x86_64"]["libripdpi.so"])

    def test_build_size_report_includes_deltas_and_totals(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            lib_dir = Path(temp_dir)
            self.write_libraries(
                lib_dir,
                {
                    "arm64-v8a": {
                        "libripdpi.so": 110,
                        "libripdpi-tunnel.so": 210,
                    },
                },
            )
            baseline = {
                "maxPerLibraryGrowthBytes": 16,
                "maxTotalGrowthPercent": 10,
                "maxTotalGrowthBytes": 40,
                "libraries": {
                    "arm64-v8a": {
                        "libripdpi.so": 100,
                        "libripdpi-tunnel.so": 200,
                    },
                },
            }

            report = verify_native_sizes.build_size_report(lib_dir, baseline)

            self.assertEqual(20, report["totals"]["deltaBytes"])
            self.assertEqual("ok", report["totals"]["status"])
            self.assertEqual([], report["failures"])
            self.assertEqual(10, report["libraries"][0]["deltaBytes"])

    def test_render_size_report_markdown_mentions_totals(self) -> None:
        report = {
            "libraries": [
                {
                    "abi": "x86_64",
                    "library": "libripdpi.so",
                    "baselineSize": 100,
                    "currentSize": 105,
                    "allowedSize": 120,
                    "deltaBytes": 5,
                    "status": "ok",
                },
            ],
            "totals": {
                "baselineSize": 100,
                "currentSize": 105,
                "allowedSize": 120,
                "deltaBytes": 5,
                "status": "ok",
            },
            "failures": [],
        }

        markdown = verify_native_sizes.render_size_report_markdown(report)

        self.assertIn("# Native Size Report", markdown)
        self.assertIn("## Totals", markdown)
        self.assertIn("`+5`", markdown)


if __name__ == "__main__":
    unittest.main()
