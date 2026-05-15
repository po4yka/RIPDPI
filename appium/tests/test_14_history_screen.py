"""Test 14: History screen sections, search, and filters."""

import pytest

from pages.history_page import HistoryPage, SECTIONS


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_history_sections_visible(driver):
    history = HistoryPage(driver)
    assert history.is_loaded(), "History screen should be visible"

    for section in SECTIONS:
        assert history.is_section_visible(section), (
            f"History section '{section}' should be visible"
        )


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_history_search_and_filter(driver):
    history = HistoryPage(driver)
    assert history.is_loaded(), "History screen should be visible"

    # Switch to connections section and search.
    history.tap_section("connections")
    history.type_search("connections", "test query")
    if not history.is_prefix_visible("history-connections-mode-", timeout=3):
        assert history.is_visible("history-connections-state-empty"), (
            "History should show an empty state when no rows match the search"
        )
        return

    history.tap_connections_mode_filter("vpn")

    # Clear all filters.
    history.tap_clear_all_filters()
