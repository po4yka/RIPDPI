from __future__ import annotations

import unittest

from scripts.bench.measure_native_abi_parallelism import (
    FULL_ABIS,
    gradle_command,
    summarize,
)


class MeasureNativeAbiParallelismTest(unittest.TestCase):
    def test_gradle_command_targets_full_abi_native_task(self) -> None:
        command = gradle_command(2, cpu_budget=6)

        self.assertIn(":core:engine:buildRustNativeLibs", command)
        self.assertIn(f"-Pripdpi.localNativeAbis={FULL_ABIS}", command)
        self.assertIn("-Pripdpi.nativeAbiParallelism=2", command)
        self.assertIn("-Pripdpi.nativeCpuBudget=6", command)
        self.assertIn("--no-build-cache", command)
        self.assertIn("--no-configuration-cache", command)

    def test_summary_uses_median_and_reports_spread(self) -> None:
        summary = summarize(
            [
                {
                    "parallelism": 2,
                    "elapsedSeconds": 10.0,
                    "peakRssBytes": 100,
                    "exitCode": 0,
                },
                {
                    "parallelism": 2,
                    "elapsedSeconds": 12.0,
                    "peakRssBytes": 120,
                    "exitCode": 0,
                },
                {
                    "parallelism": 2,
                    "elapsedSeconds": 11.0,
                    "peakRssBytes": 110,
                    "exitCode": 0,
                },
            ]
        )

        self.assertEqual(11.0, summary[0]["medianElapsedSeconds"])
        self.assertEqual(18.18, summary[0]["spreadPercent"])
        self.assertEqual(120, summary[0]["peakRssBytes"])
        self.assertTrue(summary[0]["allSucceeded"])


if __name__ == "__main__":
    unittest.main()
