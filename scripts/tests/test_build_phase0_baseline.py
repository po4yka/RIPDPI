from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from scripts.ci import build_phase0_baseline


class BuildPhase0BaselineTest(unittest.TestCase):
    def test_build_snapshot_aggregates_present_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            criterion_dir = Path(temp_dir)
            bench_dir = criterion_dir / "relay/tcp-echo/relay/1MiB/new"
            bench_dir.mkdir(parents=True)
            bench_dir.joinpath("estimates.json").write_text(
                '{"mean":{"point_estimate":1000},"median":{"point_estimate":900}}',
                encoding="utf-8",
            )

            snapshot = build_phase0_baseline.build_snapshot(
                criterion_dir=criterion_dir,
                load_report={
                    "establishedConnections": 24,
                    "processRssGrowthBytesPerEstablishedConnection": 1024,
                    "ripdpiThreadGrowthPerEstablishedConnection": 2.0,
                    "holdSeconds": 22,
                },
                wrapper_report={
                    "startup": {"meanNs": 100},
                    "shutdown": {"meanNs": 200},
                    "pollTelemetry": {"meanNs": 300},
                },
                debug_size_report={
                    "totals": {"baselineSize": 10, "currentSize": 12, "deltaBytes": 2, "status": "ok"},
                    "libraries": [{"abi": "x86_64", "library": "libripdpi.so", "deltaBytes": 2}],
                },
                release_size_report=None,
                bloat_report={
                    "target": "x86_64-linux-android",
                    "profile": "android-jni",
                    "packages": {
                        "ripdpi-android": {
                            "textSection": {"baseline": 10, "current": 12, "deltaBytes": 2, "allowed": 20, "status": "ok"},
                            "likelyDrivers": ["ripdpi_runtime"],
                            "topCrateGrowth": [],
                        },
                    },
                },
            )

            self.assertEqual(900, snapshot["criterion"]["spotlight"]["nativeThroughput"]["median_ns"])
            self.assertEqual(24, snapshot["memoryPerConnection"]["establishedConnections"])
            self.assertEqual(100, snapshot["engineWrapper"]["startup"]["meanNs"])
            self.assertIn("releaseSizeReport", snapshot["missingInputs"])

    def test_render_markdown_mentions_sections(self) -> None:
        markdown = build_phase0_baseline.render_markdown(
            {
                "unsafeAuditChecklist": "docs/native/unsafe-audit.md",
                "missingInputs": [],
                "criterion": {
                    "spotlight": {
                        "nativeThroughput": {"mean_ns": 1000, "median_ns": 900},
                        "runtimeHotPathRead": None,
                        "runtimeHotPathWriteRead": None,
                        "tcpRelaySetup": None,
                        "lockContention": None,
                    },
                },
                "memoryPerConnection": None,
                "engineWrapper": {
                    "startup": {"meanNs": 100},
                    "shutdown": {"meanNs": 200},
                    "pollTelemetry": {"meanNs": 300},
                },
                "debugNativeSize": None,
                "releaseNativeSize": None,
                "nativeBloat": None,
            },
        )

        self.assertIn("# Phase 0 Baseline Snapshot", markdown)
        self.assertIn("## Criterion Spotlight", markdown)
        self.assertIn("## Engine Wrapper", markdown)


if __name__ == "__main__":
    unittest.main()
