from __future__ import annotations

import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
STATE_MARKERS = {
    "home-modes-diagnostics-collapsed",
    "home-modes-diagnostics-expanded",
}
STABLE_HEADER = "home-modes-diagnostics-header"
TAP_TARGET = re.compile(
    r"(?m)^\s*-\s*tapOn:\s*$\n\s+id:\s*[\"']?([^\"'\s]+)",
)


class HomeDisclosureSelectorContractTest(unittest.TestCase):
    def test_maestro_flows_never_tap_state_markers(self) -> None:
        state_aware_flows: list[Path] = []
        for root in (REPO_ROOT / "maestro", REPO_ROOT / "test-lab" / "maestro"):
            for flow in root.rglob("*.yaml"):
                source = flow.read_text(encoding="utf-8")
                tap_targets = set(TAP_TARGET.findall(source))
                self.assertFalse(
                    tap_targets & STATE_MARKERS,
                    f"{flow.relative_to(REPO_ROOT)} taps a disclosure state marker",
                )
                if any(marker in source for marker in STATE_MARKERS):
                    state_aware_flows.append(flow)
                    self.assertIn(
                        STABLE_HEADER,
                        tap_targets,
                        f"{flow.relative_to(REPO_ROOT)} must tap the stable disclosure header",
                    )

        self.assertTrue(state_aware_flows, "expected state-aware Home disclosure flows")

    def test_appium_uses_state_marker_only_as_condition(self) -> None:
        home_page = (REPO_ROOT / "appium" / "pages" / "home_page.py").read_text(encoding="utf-8")

        self.assertIn("self.tap(self.MODES_DISCLOSURE)", home_page)
        self.assertNotIn("collapsed_disclosure.click()", home_page)
        self.assertNotIn("self.tap(self.MODES_COLLAPSED)", home_page)


if __name__ == "__main__":
    unittest.main()
