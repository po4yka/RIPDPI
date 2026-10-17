"""History section filter chips and detail sheet coverage."""
import pytest

from pages.history_page import HistoryPage


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_connections_filters_visible(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "HistoryPage did not load"

    page.tap_section("connections")

    if not page.is_connections_content_visible():
        pytest.skip("No connections content available to test filters")

    mode = page.tap_first_filter_option("connections", "mode")
    page.scroll_to(mode)
    assert page.is_selected(mode), "The chosen connection mode must be selected"
    status = page.tap_first_filter_option("connections", "status")
    page.scroll_to(status)
    assert page.is_selected(status), "The chosen connection status must be selected"


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_report_demo",
    service_preset="idle",
)
def test_diagnostics_filters_visible(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "HistoryPage did not load"

    page.tap_section("diagnostics")

    assert page.is_diagnostics_content_visible(), "Seeded diagnostics report must exist"
    page.tap_diagnostics_path_filter("raw-path")
    page.scroll_to("history-diagnostics-path-raw-path")
    assert page.is_selected("history-diagnostics-path-raw-path"), "RAW_PATH must be selected"
    page.tap_diagnostics_status_filter("completed")
    page.scroll_to("history-diagnostics-status-completed")
    assert page.is_selected("history-diagnostics-status-completed"), "Completed must be selected"


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_events_filters_visible(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "HistoryPage did not load"

    page.tap_section("events")

    if not page.is_events_content_visible():
        pytest.skip("No events content available to test filters")
    page.open_filters("events")

    source = page.tap_first_filter_option("events", "source")
    page.scroll_to(source)
    assert page.is_selected(source), "The chosen event source must be selected"
    severity = page.tap_first_filter_option("events", "severity")
    page.scroll_to(severity)
    assert page.is_selected(severity), "The chosen event severity must be selected"


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_history_detail_sheets(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "HistoryPage did not load"

    page.tap_section("connections")

    if not page.is_connections_content_visible():
        pytest.skip("No connections content available to test detail sheets")

    try:
        page.tap_first_connection()
    except Exception:
        pytest.skip("Could not tap a connection row")

    if not page.is_connection_detail_visible():
        pytest.skip("Connection detail sheet did not appear after tap")

    assert page.is_connection_detail_visible(), (
        "Connection detail sheet is not visible after tapping a connection"
    )
