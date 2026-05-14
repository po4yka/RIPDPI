"""Workflow 05: Diagnostics -> scan section -> swipe to share -> verify share button."""

import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.workflow
@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_scan_and_share(workflow_app):
    driver = workflow_app

    # Step 1: Verify diagnostics screen loaded.
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    # Step 2: Verify dashboard section visible first.
    assert diag.is_section_visible("dashboard"), "Dashboard section should be initial"

    # Step 3: Swipe to scan section.
    diag.swipe_to_next_section()
    diag.wait_until(
        lambda: diag.is_section_visible("scan"),
        timeout=5,
        message="Scan section should be visible after swipe",
    )

    # Step 4: Swipe to tools section.
    diag.swipe_to_next_section()

    # Step 5: Verify share/save buttons are visible.
    diag.scroll_incrementally_to(DiagnosticsPage.SHARE_ARCHIVE)
    diag.wait_until(
        lambda: diag.is_visible(DiagnosticsPage.SHARE_ARCHIVE),
        timeout=5,
        message="Share archive button should be visible in tools section",
    )
    diag.scroll_incrementally_to(DiagnosticsPage.SAVE_ARCHIVE)
    assert diag.is_visible(DiagnosticsPage.SAVE_ARCHIVE), (
        "Save archive button should be visible"
    )
