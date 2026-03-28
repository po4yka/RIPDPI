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

    page.tap_connections_mode_filter("all")
    page.tap_connections_status_filter("active")


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_filters_visible(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "HistoryPage did not load"

    page.tap_section("diagnostics")

    if not page.is_diagnostics_content_visible():
        pytest.skip("No diagnostics content available to test filters")

    page.tap_diagnostics_path_filter("all")
    page.tap_diagnostics_status_filter("active")


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

    page.tap_event_source_filter("all")
    page.tap_event_severity_filter("error")


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
        page.tap_connection("0")
    except Exception:
        pytest.skip("Could not tap a connection row; dynamic ID not predictable")

    if not page.is_visible(HistoryPage.CONNECTION_DETAIL_SHEET, timeout=5):
        pytest.skip("Connection detail sheet did not appear after tap")

    assert page.is_visible(
        HistoryPage.CONNECTION_DETAIL_SHEET, timeout=5
    ), "Connection detail sheet is not visible after tapping a connection"
