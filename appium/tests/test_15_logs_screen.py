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

    # Toggle auto-scroll.
    logs.toggle_auto_scroll()

    # Clear and save should be tappable without error.
    logs.tap_clear()
    logs.tap_save()
