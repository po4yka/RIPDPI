"""Diagnostics search, filter, and tools section coverage."""
import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_tools_section_actions(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    page.swipe_to_tools_section()

    assert page.is_visible(
        DiagnosticsPage.SAVE_LOGS
    ), "Save logs button is not visible on the tools section"
    assert page.is_visible(
        DiagnosticsPage.SHARE_SUMMARY
    ), "Share summary button is not visible on the tools section"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_approach_chips_in_tools(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    page.swipe_to_tools_section()

    assert page.is_visible(
        DiagnosticsPage.APPROACH_MODE_PROFILES
    ), "Profiles mode chip is not visible on the tools section"
    assert page.is_visible(
        DiagnosticsPage.APPROACH_MODE_STRATEGIES
    ), "Strategies mode chip is not visible on the tools section"
