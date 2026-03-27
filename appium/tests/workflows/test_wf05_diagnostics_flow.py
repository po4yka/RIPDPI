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

    # Step 2: Verify overview section visible first.
    assert diag.is_section_visible("overview"), "Overview section should be initial"

    # Step 3: Swipe to scan section.
    diag.swipe_to_next_section()
    diag.wait_until(
        lambda: diag.is_section_visible("scan"),
        timeout=5,
        message="Scan section should be visible after swipe",
    )

    # Step 4: Swipe past live and approaches to share section.
    diag.swipe_to_next_section()  # live
    diag.swipe_to_next_section()  # approaches
    diag.swipe_to_next_section()  # share

    # Step 5: Verify share/save buttons are visible.
    diag.wait_until(
        lambda: diag.is_visible("diagnostics-share-archive"),
        timeout=5,
        message="Share archive button should be visible in share section",
    )
    assert diag.is_visible("diagnostics-save-archive"), (
        "Save archive button should be visible"
    )
