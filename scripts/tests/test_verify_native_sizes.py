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


if __name__ == "__main__":
    unittest.main()
