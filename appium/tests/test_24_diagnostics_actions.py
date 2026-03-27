"""Test 24: Diagnostics scan actions and share/save buttons."""

import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_share_save_buttons(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    # Swipe to the share section (last section).
    for _ in range(4):
        diag.swipe_to_next_section()

    assert diag.is_visible("diagnostics-share-archive"), (
        "Share archive button should be visible in share section"
    )
    assert diag.is_visible("diagnostics-save-archive"), (
        "Save archive button should be visible in share section"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_scan_actions(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    # Swipe to the scan section (second section).
    diag.swipe_to_next_section()

    assert diag.is_section_visible("scan"), "Scan section should be visible"

    # Check scan action buttons are present.
    assert diag.is_visible("diagnostics-scan-run-raw") or diag.is_visible(
        "diagnostics-scan-state-idle"
    ), "Scan section should show either run button or idle state"
