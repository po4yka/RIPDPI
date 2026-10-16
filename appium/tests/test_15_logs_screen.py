"""Test 15: Logs screen controls and filters."""

import pytest

from pages.logs_page import LogsPage


@pytest.mark.automation(
    start_route="logs",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_logs_controls(driver):
    logs = LogsPage(driver)
    assert logs.is_loaded(), "Logs screen should be visible"

    logs.open_filters()
    logs.toggle_auto_scroll()
    logs.close_sheet()
    assert logs.is_loaded(), "Log stream should return after closing filters"

    logs.open_actions()
    logs.tap_clear()
    assert logs.is_loaded(), "Clear should return to the log stream"
    logs.open_actions()
    logs.tap_save()
