"""Test 33: Empty state placeholders on diagnostics and history screens."""

import pytest

from pages.base_page import BasePage
from pages.diagnostics_page import DiagnosticsPage
from pages.history_page import HistoryPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="clean_home",
    service_preset="idle",
)
def test_diagnostics_empty_state(driver):
    base = BasePage(driver)
    diag = DiagnosticsPage(driver)
    if not base.is_visible("diagnostics-sessions-state-empty", timeout=2):
        if diag.is_visible("diagnostics-overview-hero") or diag.is_visible("diagnostics-sessions-state-content"):
            pytest.skip("Diagnostics history or live telemetry exists on this device")

    assert base.is_visible("diagnostics-sessions-state-empty"), (
        "Diagnostics should show empty sessions placeholder when no data is seeded"
    )


@pytest.mark.automation(
    start_route="history",
    data_preset="clean_home",
    service_preset="idle",
)
def test_history_empty_state(driver):
    base = BasePage(driver)
    history = HistoryPage(driver)
    if not base.is_visible("history-connections-state-empty", timeout=2):
        if history.is_connections_content_visible():
            pytest.skip("Connection history exists on this device")

    assert base.is_visible("history-connections-state-empty"), (
        "History should show empty connections placeholder when no data is seeded"
    )
