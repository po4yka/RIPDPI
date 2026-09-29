"""Test 37: Approach mode switching between Profiles and Strategies chips."""

import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_approach_chips_visible(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_tools_section()
    assert diag.is_section_visible("tools"), "Tools section should be visible"

    assert diag.is_visible(DiagnosticsPage.APPROACH_MODE_PROFILES), (
        "Profiles mode chip should be visible"
    )
    assert diag.is_visible(DiagnosticsPage.APPROACH_MODE_STRATEGIES), (
        "Strategies mode chip should be visible"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_approach_switch_to_strategies(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_tools_section()
    assert diag.is_section_visible("tools"), "Tools section should be visible"

    # Switch to strategies mode.
    diag.tap_approach_mode_strategies()
    diag.wait_until(lambda: diag.is_selected(DiagnosticsPage.APPROACH_MODE_STRATEGIES))
    assert diag.is_selected(DiagnosticsPage.APPROACH_MODE_STRATEGIES), (
        "Strategies mode should be selected"
    )
    assert not diag.is_selected(DiagnosticsPage.APPROACH_MODE_PROFILES), (
        "Profiles mode should be deselected"
    )

    # Switch back to profiles mode.
    diag.tap_approach_mode_profiles()
    diag.wait_until(lambda: diag.is_selected(DiagnosticsPage.APPROACH_MODE_PROFILES))
    assert diag.is_selected(DiagnosticsPage.APPROACH_MODE_PROFILES), (
        "Profiles mode should be selected after switching back"
    )
    assert not diag.is_selected(DiagnosticsPage.APPROACH_MODE_STRATEGIES), (
        "Strategies mode should be deselected after switching back"
    )
