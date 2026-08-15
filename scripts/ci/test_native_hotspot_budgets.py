#!/usr/bin/env python3

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import check_native_hotspot_budgets as sut


class NativeHotspotBudgetTests(unittest.TestCase):
    def test_cfg_test_fixture_module_has_no_production_loc(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "native/rust/crates/example/src/test_fixtures.rs"
            source.parent.mkdir(parents=True)
            source.write_text("pub(crate) fn fixture() {}\n", encoding="utf-8")

            measurement = sut.measure_hotspot(
                root,
                sut.HotspotBudget(
                    path="native/rust/crates/example/src/test_fixtures.rs",
                    max_production_loc=0,
                ),
            )

            self.assertEqual(measurement.measured_production_loc, 0)

    def test_included_children_aggregate_into_parent_budget(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = root / "native/rust/crates/example/src/coordinator.rs"
            child = root / "native/rust/crates/example/src/coordinator/run_loop.rs"
            nested_child = root / "native/rust/crates/example/src/coordinator/run_loop/parallel.rs"
            nested_child.parent.mkdir(parents=True)
            parent.write_text("mod run_loop;\n", encoding="utf-8")
            child.write_text("pub(crate) fn child() {}\n", encoding="utf-8")
            nested_child.write_text("\n".join("pub(crate) fn step() {}" for _ in range(10)), encoding="utf-8")

            measurements = sut.measure_hotspots(
                root,
                [
                    sut.HotspotBudget(
                        path="native/rust/crates/example/src/coordinator.rs",
                        max_production_loc=10,
                        include_children=True,
                    ),
                ],
            )

            self.assertEqual([item.path for item in measurements], ["native/rust/crates/example/src/coordinator.rs"])
            self.assertEqual(measurements[0].max_production_loc, 10)
            self.assertEqual(measurements[0].measured_production_loc, 12)
            self.assertIn("Over budget: 1", sut.format_summary(measurements))

    def test_explicit_child_budget_stops_parent_inheritance(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            parent = root / "native/rust/crates/example/src/lanes.rs"
            explicit_child = root / "native/rust/crates/example/src/lanes/https.rs"
            nested_child = root / "native/rust/crates/example/src/lanes/https/attempt.rs"
            nested_child.parent.mkdir(parents=True)
            parent.write_text("mod https;\n", encoding="utf-8")
            explicit_child.write_text("mod attempt;\n", encoding="utf-8")
            nested_child.write_text("\n".join("pub(crate) fn step() {}" for _ in range(11)), encoding="utf-8")

            measurements = sut.measure_hotspots(
                root,
                [
                    sut.HotspotBudget(
                        path="native/rust/crates/example/src/lanes.rs",
                        max_production_loc=10,
                        include_children=True,
                    ),
                    sut.HotspotBudget(
                        path="native/rust/crates/example/src/lanes/https.rs",
                        max_production_loc=20,
                    ),
                ],
            )

            self.assertEqual(
                [item.path for item in measurements],
                [
                    "native/rust/crates/example/src/lanes.rs",
                    "native/rust/crates/example/src/lanes/https.rs",
                ],
            )
            self.assertEqual(measurements[0].measured_production_loc, 1)
            self.assertEqual(measurements[1].measured_production_loc, 1)


if __name__ == "__main__":
    unittest.main()
