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

    logs.open_filters()
    tag = "logs-subsystem-proxy"
    logs.scroll_incrementally_to(tag)
    before = logs.is_selected(tag)
    logs.select_subsystem_filter("proxy")
    logs.wait_until(lambda: logs.is_selected(tag) != before)
    logs.close_sheet()
    assert logs.is_loaded(), "Logs screen should remain visible after proxy filter"

    logs.open_filters()
    tag = "logs-subsystem-service"
    logs.scroll_incrementally_to(tag)
    before = logs.is_selected(tag)
    logs.select_subsystem_filter("service")
    logs.wait_until(lambda: logs.is_selected(tag) != before)
    logs.close_sheet()
    assert logs.is_loaded(), "Logs screen should remain visible after service filter"


@pytest.mark.automation(
    start_route="logs",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_logs_severity_filters(driver):
    logs = LogsPage(driver)
    assert logs.is_loaded(), "Logs screen should be visible"

    logs.open_filters()
    tag = "logs-severity-error"
    logs.scroll_incrementally_to(tag)
    before = logs.is_selected(tag)
    logs.select_severity_filter("error")
    logs.wait_until(lambda: logs.is_selected(tag) != before)
    logs.close_sheet()
    assert logs.is_loaded(), "Logs screen should remain visible after error filter"

    logs.open_filters()
    tag = "logs-severity-warn"
    logs.scroll_incrementally_to(tag)
    before = logs.is_selected(tag)
    logs.select_severity_filter("warn")
    logs.wait_until(lambda: logs.is_selected(tag) != before)
    logs.close_sheet()
    assert logs.is_loaded(), "Logs screen should remain visible after warn filter"
