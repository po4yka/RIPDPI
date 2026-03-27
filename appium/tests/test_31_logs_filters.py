"""Test 31: Logs screen subsystem and severity filter chip interactions."""

import pytest

from pages.logs_page import LogsPage


@pytest.mark.automation(
    start_route="logs",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_logs_subsystem_filters(driver):
    logs = LogsPage(driver)
    assert logs.is_loaded(), "Logs screen should be visible"

    # Tap subsystem filter chips.
    logs.select_subsystem_filter("proxy")
    assert logs.is_loaded(), "Logs screen should remain visible after proxy filter"

    logs.select_subsystem_filter("service")
    assert logs.is_loaded(), "Logs screen should remain visible after service filter"


@pytest.mark.automation(
    start_route="logs",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_logs_severity_filters(driver):
    logs = LogsPage(driver)
    assert logs.is_loaded(), "Logs screen should be visible"

    # Tap severity filter chips.
    logs.select_severity_filter("error")
    assert logs.is_loaded(), "Logs screen should remain visible after error filter"

    logs.select_severity_filter("warn")
    assert logs.is_loaded(), "Logs screen should remain visible after warn filter"
