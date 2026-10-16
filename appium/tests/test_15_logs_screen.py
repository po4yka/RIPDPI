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
    before = logs.is_checked(logs.AUTO_SCROLL)
    logs.toggle_auto_scroll()
    logs.wait_until(lambda: logs.is_checked(logs.AUTO_SCROLL) != before)
    logs.close_sheet()
    assert logs.is_visible(logs.EMPTY_STATE), "The local buffer should start empty"

    logs.open_actions()
    assert not logs.wait_for(logs.CLEAR).is_enabled(), "An empty buffer cannot be cleared"
    logs.tap_save()
    logs.wait_for("export-preview-content", timeout=30)
    logs.tap("export-preview-cancel")
    logs.wait_until(lambda: not logs.is_visible("export-preview-content", timeout=1))
    assert logs.is_loaded(), "Cancel should return to logs without exporting"
