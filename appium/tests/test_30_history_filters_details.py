"""Test 30: History screen filter chips and detail sheet interactions."""

import pytest

from pages.base_page import BasePage
from pages.history_page import HistoryPage


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_history_events_auto_scroll(driver):
    history = HistoryPage(driver)
    assert history.is_loaded(), "History screen should be visible"

    # Switch to events section.
    history.tap_section("events")
    assert history.is_section_visible("events"), (
        "Events section tab should be visible"
    )

    # Toggle auto-scroll.
    assert history.is_auto_scroll_visible(), (
        "Auto-scroll toggle should be visible in events section"
    )
    history.tap_auto_scroll()


@pytest.mark.automation(
    start_route="history",
    data_preset="clean_home",
    service_preset="idle",
)
def test_history_empty_states(driver):
    history = HistoryPage(driver)
    assert history.is_loaded(), "History screen should be visible"

    # With no data, connections should show empty state.
    page = BasePage(driver)
    assert page.is_visible("history-connections-state-empty"), (
        "Connections empty state should be visible when no data is available"
    )
