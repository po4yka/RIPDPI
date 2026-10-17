"""History filters select exact options and retain saved report data."""

import re

import pytest
from selenium.common.exceptions import TimeoutException

from pages.history_page import HistoryPage

SEARCH = "history-diagnostics-search"
DISCLOSURE = f"{SEARCH}-filter-disclosure"
PATH = "history-diagnostics-path-raw-path"
STATUS = "history-diagnostics-status-completed"
REPORT = "history-diagnostics-automation-report-demo-session"


def _filter_count(page: HistoryPage) -> int:
    page.scroll_to(DISCLOSURE)
    number = re.search(r"\d+", page.get_text(DISCLOSURE))
    assert number is not None, "Filter disclosure must expose its active count"
    return int(number.group())


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_report_demo",
    service_preset="idle",
)
def test_diagnostics_exact_filter_lifecycle_retains_history(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "History screen must load"
    page.tap_section("diagnostics")
    assert page.is_diagnostics_content_visible(), "Seeded diagnostics history must exist"

    page.open_filters("diagnostics")
    assert page.is_selected(DISCLOSURE), "Filter choices must be expanded"
    assert _filter_count(page) == 0, "Initial report filters must be clear"

    page.tap_diagnostics_path_filter("raw-path")
    page.scroll_to(PATH)
    assert page.is_selected(PATH), "The exact RAW_PATH option must be selected"
    assert _filter_count(page) == 1, "Path selection must add one active filter"
    page.tap_diagnostics_status_filter("completed")
    page.scroll_to(STATUS)
    assert page.is_selected(STATUS), "The exact completed option must be selected"
    assert _filter_count(page) == 2, "Status selection must add one active filter"
    page.scroll_to(SEARCH)
    page.type_search("diagnostics", "Automation")
    assert "Automation" in page.get_text(SEARCH), "Search query must remain intact"
    assert _filter_count(page) == 3, "Query, path, and status must all be active"

    page.scroll_to(PATH)
    for _ in range(2):
        page.open_filters("diagnostics")
        assert page.is_selected(DISCLOSURE), "Repeated open must not collapse choices"
        assert _filter_count(page) == 3, "Opening choices must preserve all filters"

    page.tap_section("connections")
    page.tap_section("diagnostics")
    page.open_filters("diagnostics")
    assert _filter_count(page) == 3, "Section changes must retain diagnostics filters"
    page.scroll_to(PATH)
    assert page.is_selected(PATH), "Path selection must survive section changes"
    page.scroll_to(STATUS)
    assert page.is_selected(STATUS), "Status selection must survive section changes"

    page.scroll_to(f"{SEARCH}-filter-remove-secondary").click()
    assert _filter_count(page) == 2, "Removing status must retain query and path"
    page.scroll_to(SEARCH)
    assert "Automation" in page.get_text(SEARCH), "Removing status must retain the query"
    page.scroll_to(PATH)
    assert page.is_selected(PATH), "Removing status must retain the path"

    page.scroll_to("history-filter-clear-all")
    page.tap_clear_all_filters()
    assert _filter_count(page) == 0, "Clear all must remove every active filter"
    page.scroll_to(REPORT)
    assert page.is_visible(REPORT), "Clearing filters must retain the saved report"

    page.scroll_to(SEARCH)
    page.type_search("diagnostics", "missing-record-7d4f07")
    page.scroll_to("history-no-results")
    assert page.is_visible("history-no-results"), "Unmatched query must show no results"
    page.scroll_to("history-empty-clear-filters").click()
    assert _filter_count(page) == 0, "Empty-result recovery must clear the query"
    page.scroll_to(REPORT)
    assert page.is_visible(REPORT), "Empty-result recovery must restore the same saved report"
    assert not page.is_visible("history-diagnostics-detail-sheet", timeout=1), (
        "Filter interaction must not open report details"
    )


@pytest.mark.automation(
    start_route="history",
    data_preset="diagnostics_report_demo",
    service_preset="idle",
)
def test_missing_exact_status_does_not_select_another_category(driver):
    page = HistoryPage(driver)
    assert page.is_loaded(), "History screen must load"
    page.tap_section("diagnostics")
    page.open_filters("diagnostics")
    with pytest.raises(TimeoutException):
        page.tap_diagnostics_status_filter("missing-status-7d4f07")
    assert _filter_count(page) == 0, "Missing option must not change any filter"
    page.scroll_to(STATUS)
    assert not page.is_selected(STATUS), "Missing option must not select completed instead"
    page.scroll_to(REPORT)
    assert page.is_visible(REPORT), "Missing option must retain the same saved report"
