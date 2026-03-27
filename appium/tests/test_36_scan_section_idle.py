"""Test 36: Scan section idle state -- profile picker and run buttons."""

import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_scan_section_idle_state(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_scan_section()
    assert diag.is_section_visible("scan"), "Scan section should be visible"

    assert diag.is_scan_idle(), "Scan section should be in idle state"
    assert diag.is_visible(DiagnosticsPage.SCAN_RUN_RAW), (
        "Run Raw button should be visible in idle state"
    )
    assert diag.is_visible(DiagnosticsPage.SCAN_RUN_IN_PATH), (
        "Run In-Path button should be visible in idle state"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_scan_section_profile_visible(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_scan_section()
    assert diag.is_section_visible("scan"), "Scan section should be visible"

    assert diag.is_visible("diagnostics-profile-default"), (
        "Default profile card should be visible (seeded by diagnostics_demo preset)"
    )
