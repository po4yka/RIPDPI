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
    assert diag.is_visible(DiagnosticsPage.APPROACH_MODE_STRATEGIES), (
        "Strategies chip should remain visible after selection"
    )

    # Switch back to profiles mode.
    diag.tap_approach_mode_profiles()
    assert diag.is_visible(DiagnosticsPage.APPROACH_MODE_PROFILES), (
        "Profiles chip should remain visible after switching back"
    )
