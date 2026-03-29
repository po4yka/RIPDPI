"""Test 08: Swipe through all 3 diagnostics pager sections."""

import pytest

from pages.diagnostics_page import DiagnosticsPage, SECTIONS


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_pager(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    # The first section should be visible without swiping.
    assert diag.is_section_visible(SECTIONS[0]), (
        f"First section '{SECTIONS[0]}' should be visible on load"
    )

    # Swipe through remaining sections.
    for section in SECTIONS[1:]:
        diag.swipe_to_next_section()
        assert diag.is_section_visible(section), (
            f"Section '{section}' should be visible after swiping"
        )
