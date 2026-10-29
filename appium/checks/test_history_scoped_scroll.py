"""Host guards for History's exact, container-scoped Appium interactions."""

from __future__ import annotations

import unittest

from selenium.common.exceptions import TimeoutException

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
        raise AssertionError(f"Unsupported scroll backend: {command}")

    def swipe(self, start_x, start_y, end_x, end_y, duration):
        self.gestures.append({
            "start_x": start_x, "start_y": start_y,
            "end_x": end_x, "end_y": end_y, "duration": duration,
            "direction": "down" if end_y < start_y else "up",
        })
        if self.on_scroll:
            self.on_scroll()


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
        gesture = driver.gestures[0]
        self.assertEqual(540, gesture["start_x"])
        self.assertEqual(540, gesture["end_x"])
        self.assertGreater(gesture["start_y"], 742)
        self.assertLess(gesture["start_y"], 1848)
        self.assertGreater(gesture["end_y"], 742)
        self.assertLess(gesture["end_y"], 1848)
        self.assertEqual("down", gesture["direction"])

    def test_missing_exact_target_never_clicks_an_available_other_option(self):
        other = FakeElement("history-diagnostics-status-failed", {"x": 108, "y": 900, "width": 864, "height": 160})
        page = FakePage({self.BODY: self.body(), other.tag: other})

        with self.assertRaises(TimeoutException):
            page.tap_in_container(self.TARGET, self.BODY)

        self.assertEqual(0, other.clicks)
        self.assertEqual(["down"] * 5 + ["up"] * 5, [g["direction"] for g in page.driver.gestures])

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
        self.assertGreater(driver.gestures[0]["start_y"], 742)
        self.assertLess(driver.gestures[0]["end_y"], 1848)

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

    def test_section_switch_waits_for_stable_tab_and_selected_body_before_return(self):
        body = self.body()

        class MovingTab:
            def __init__(self):
                self.observations = []
                self.clicked = False
                self.positions = [100, 130, 130]

            @property
            def rect(self):
                x = self.positions[min(len(self.observations), len(self.positions) - 1)]
                self.observations.append(x)
                return {"x": x, "y": 527, "width": 450, "height": 180}

            def is_displayed(self):
                return True

            def is_enabled(self):
                return True

            def get_attribute(self, name):
                return {
                    "resource-id": "history-section-diagnostics",
                    "checkable": "true", "checked": str(self.clicked).lower(),
                }.get(name)

            def click(self):
                if len(self.observations) < 2 or self.observations[-1] != self.observations[-2]:
                    raise AssertionError("The tab must stop moving before its single click")
                self.clicked = True

        tab = MovingTab()

        class SwitchingHistory(HistoryPage, FakePage):
            def __init__(self):
                FakePage.__init__(self, {"history-section-diagnostics": tab})
                self._section = "connections"
                self.body_reads = 0

            def _find_by_test_tag(self, tag):
                if tag == "history-diagnostics-list":
                    self.body_reads += 1
                    return body if self.body_reads >= 3 and tab.clicked else False
                return self.elements.get(tag, False)

        page = SwitchingHistory()
        page.tap_section("diagnostics")
        self.assertTrue(tab.clicked)
        self.assertGreaterEqual(page.body_reads, 3)
        self.assertEqual("diagnostics", page._section)

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
