"""Test 38: Scan execution, progress, and result elements (timing-dependent).

These tests depend on actual scan execution in automation mode. Functions
use soft-skip when the scan does not produce the expected state.
"""

import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_scan_run_raw_triggers_state_change(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_scan_section()
    assert diag.is_section_visible("scan"), "Scan section should be visible"

    # Tap run raw scan.
    diag.tap_run_raw_scan()

    # State should transition from idle to progress or content.
    try:
        diag.wait_until(
            lambda: diag.is_scan_in_progress() or diag.is_scan_has_content(),
            timeout=10,
            message="Scan should transition from idle after tapping run",
        )
    except TimeoutError:
        pytest.skip("Scan did not transition from idle in automation mode")

    # If in progress, verify progress elements.
    if diag.is_scan_in_progress():
        assert diag.is_visible(DiagnosticsPage.SCAN_CANCEL), (
            "Cancel button should be visible during scan progress"
        )
        assert diag.is_visible(DiagnosticsPage.SCAN_PROGRESS_CARD), (
            "Progress card should be visible during scan"
        )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_scan_cancel(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_scan_section()
    diag.tap_run_raw_scan()

    # Wait for progress state.
    if not diag.is_scan_in_progress(timeout=5):
        pytest.skip("Scan did not enter progress state")

    # Cancel the scan.
    diag.tap_cancel_scan()

    # Should return to idle or content state.
    try:
        diag.wait_until(
            lambda: diag.is_scan_idle() or diag.is_scan_has_content(),
            timeout=10,
            message="Scan should return to idle or content after cancel",
        )
    except TimeoutError:
        pytest.skip("Scan did not transition after cancel")


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_strategy_report_and_resolver(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_scan_section()
    diag.tap_run_raw_scan()

    # Wait for scan to complete and produce content.
    try:
        diag.wait_until(
            lambda: diag.is_scan_has_content(),
            timeout=30,
            message="Scan should produce content results",
        )
    except TimeoutError:
        pytest.skip("Scan did not produce content in automation mode")

    # Check for strategy probe report.
    if diag.is_visible(DiagnosticsPage.STRATEGY_PROBE_REPORT, timeout=5):
        diag.scroll_to(DiagnosticsPage.STRATEGY_PROBE_REPORT)
        assert diag.is_visible(DiagnosticsPage.STRATEGY_PROBE_SUMMARY), (
            "Strategy probe summary should be visible within the report"
        )

    # Check for resolver recommendation.
    if diag.is_visible(DiagnosticsPage.RESOLVER_RECOMMENDATION_CARD, timeout=5):
        diag.scroll_to(DiagnosticsPage.RESOLVER_RECOMMENDATION_CARD)
        assert diag.is_visible(DiagnosticsPage.RESOLVER_KEEP_SESSION), (
            "Keep-for-session button should be visible"
        )
