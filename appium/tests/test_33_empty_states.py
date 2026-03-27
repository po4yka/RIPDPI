"""Test 33: Empty state placeholders on diagnostics and history screens."""

import pytest

from pages.base_page import BasePage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="clean_home",
    service_preset="idle",
)
def test_diagnostics_empty_state(driver):
    base = BasePage(driver)
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
    assert base.is_visible("history-connections-state-empty"), (
        "History should show empty connections placeholder when no data is seeded"
    )
