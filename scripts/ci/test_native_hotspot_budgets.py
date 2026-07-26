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


if __name__ == "__main__":
    unittest.main()
