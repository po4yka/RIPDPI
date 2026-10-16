"""Test 29: Tapping diagnostics items opens bottom detail sheets."""

import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_overview_hero(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    assert diag.is_section_visible("dashboard"), (
        "Dashboard section should be visible on load"
    )
    assert diag.is_visible("diagnostics-overview-hero"), (
        "Dashboard hero card should be visible"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_approach_detail(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_tools_section()
    diag.expand_advanced()

    assert diag.is_section_visible("tools"), (
        "Tools section should be visible after swiping"
    )

    assert diag.is_visible(diag.APPROACH_MODE_PROFILES), (
        "Profiles approach chip should be visible in tools"
    )
    assert diag.is_visible(diag.APPROACH_MODE_STRATEGIES), (
        "Strategies approach chip should be visible in tools"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_technical_disclosure_preserves_primary_action_label(driver):
    diag = DiagnosticsPage(driver)
    diag.scroll_incrementally_to(diag.OVERVIEW_RUN)
    before = diag.get_text(diag.OVERVIEW_RUN)
    assert before, "The primary action must have an accessible label"
    diag.scroll_incrementally_to(diag.EXPERT_TOGGLE).click()
    diag.scroll_incrementally_to(diag.OVERVIEW_RUN)
    assert diag.get_text(diag.OVERVIEW_RUN) == before
