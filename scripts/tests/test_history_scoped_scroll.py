"""Host guards for History's exact, container-scoped Appium interactions."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

from selenium.common.exceptions import TimeoutException

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "appium"))
from pages.base_page import BasePage
from pages.history_page import HistoryPage


class FakeElement:
    def __init__(self, tag, rect, displayed=True, enabled=True):
        self.tag = tag
        self.rect = rect
        self.displayed = displayed
        self.enabled = enabled
        self.clicks = 0

    def is_displayed(self):
        return self.displayed

    def is_enabled(self):
        return self.enabled

    def get_attribute(self, name):
        return self.tag if name == "resource-id" else None

    def click(self):
        self.clicks += 1


class FakeDriver:
    def __init__(self, on_scroll=None):
        self.gestures = []
        self.on_scroll = on_scroll

    def execute_script(self, command, args):
        if command != "mobile: scrollGesture":
            raise AssertionError(f"Unexpected command {command}")
        self.gestures.append(args)
        if self.on_scroll:
            self.on_scroll()
        return False


class FakePage(BasePage):
    def __init__(self, elements, driver=None):
        super().__init__(driver or FakeDriver())
        self.elements = elements

    def _find_by_test_tag(self, tag):
        return self.elements.get(tag, False)

    def wait_for(self, tag, timeout=10):
        return self.find(tag)


class HistoryScopedScrollTest(unittest.TestCase):
    BODY = "history-diagnostics-list"
    TARGET = "history-diagnostics-status-completed"

    def body(self):
        return FakeElement(self.BODY, {"x": 0, "y": 742, "width": 1080, "height": 1106})

    def test_partial_target_scrolls_only_inside_vertical_body_and_refinds(self):
        clipped = FakeElement(self.TARGET, {"x": 108, "y": 1780, "width": 864, "height": 68})
        visible = FakeElement(self.TARGET, {"x": 108, "y": 1000, "width": 864, "height": 160})
        tabs = FakeElement("tabs", {"x": 0, "y": 527, "width": 1080, "height": 191})
        elements = {self.BODY: self.body(), self.TARGET: clipped, "tabs": tabs}
        driver = FakeDriver(lambda: elements.update({self.TARGET: visible}))
        page = FakePage(elements, driver)

        page.tap_in_container(self.TARGET, self.BODY)

        self.assertEqual(0, clipped.clicks)
        self.assertEqual(1, visible.clicks)
        self.assertEqual(1, len(driver.gestures))
        area = driver.gestures[0]
        self.assertGreater(area["top"], 742)
        self.assertLess(area["top"] + area["height"], 1848)
        self.assertEqual("down", area["direction"])

    def test_missing_exact_target_never_clicks_an_available_other_option(self):
        other = FakeElement("history-diagnostics-status-failed", {"x": 108, "y": 900, "width": 864, "height": 160})
        page = FakePage({self.BODY: self.body(), other.tag: other})

        with self.assertRaises(TimeoutException):
            page.tap_in_container(self.TARGET, self.BODY)

        self.assertEqual(0, other.clicks)
        self.assertEqual(["down", "up"], [g["direction"] for g in page.driver.gestures])

    def test_recycled_element_identity_is_not_clicked(self):
        wrong = FakeElement("history-diagnostics-report-row", {"x": 108, "y": 900, "width": 864, "height": 160})
        page = FakePage({self.BODY: self.body(), self.TARGET: wrong})

        with self.assertRaises(TimeoutException):
            page.tap_in_container(self.TARGET, self.BODY)

        self.assertEqual(0, wrong.clicks)

    def test_disabled_and_hidden_targets_are_not_clicked(self):
        for displayed, enabled in [(False, True), (True, False)]:
            target = FakeElement(self.TARGET, {"x": 108, "y": 900, "width": 864, "height": 160}, displayed, enabled)
            page = FakePage({self.BODY: self.body(), self.TARGET: target})
            with self.assertRaises(TimeoutException):
                page.tap_in_container(self.TARGET, self.BODY)
            self.assertEqual(0, target.clicks)

    def test_explicit_first_available_option_is_scrolled_and_clicked_by_exact_id(self):
        elements = {self.BODY: self.body()}
        choice = FakeElement(self.TARGET, {"x": 108, "y": 900, "width": 864, "height": 160})
        driver = FakeDriver(lambda: elements.update({choice.tag: choice}))

        class AvailableHistory(HistoryPage, FakePage):
            def __init__(self):
                FakePage.__init__(self, elements, driver)
                self._section = "diagnostics"

            def open_filters(self, section):
                pass

            def _find_by_test_tag_prefix(self, prefix):
                return next((e for tag, e in self.elements.items() if tag.startswith(prefix)), False)

        page = AvailableHistory()
        selected = page.tap_first_filter_option("diagnostics", "status")
        self.assertEqual(self.TARGET, selected)
        self.assertEqual(1, choice.clicks)
        self.assertEqual(1, len(driver.gestures))
        self.assertGreater(driver.gestures[0]["top"], 742)

    def test_external_latest_action_does_not_use_the_events_list_container(self):
        latest = FakeElement("history-events-jump-to-latest", {"x": 60, "y": 1700, "width": 960, "height": 130})

        class ExternalActionHistory(HistoryPage):
            def wait_for(self, tag, timeout=10):
                if tag != latest.tag:
                    raise AssertionError(f"Unexpected lookup: {tag}")
                return latest

            def tap_in_container(self, *args, **kwargs):
                raise AssertionError("Latest action is outside the event list")

        page = ExternalActionHistory(FakeDriver())
        page.tap(latest.tag)
        self.assertEqual(1, latest.clicks)

    def test_history_category_request_uses_exact_target_without_prefix_fallback(self):
        class RecordingHistory(HistoryPage):
            def __init__(self):
                super().__init__(FakeDriver())
                self.requested = []

            def open_filters(self, section):
                self.requested.append(section)

            def tap(self, tag, timeout=10):
                self.requested.append(tag)
                raise TimeoutException("Missing exact option")

            def tap_first_with_prefix(self, prefix, timeout=10):
                raise AssertionError("A category must not choose a different option")

        page = RecordingHistory()
        with self.assertRaises(TimeoutException):
            page.tap_diagnostics_status_filter("missing")
        self.assertEqual(["diagnostics", "history-diagnostics-status-missing"], page.requested)


if __name__ == "__main__":
    unittest.main()
