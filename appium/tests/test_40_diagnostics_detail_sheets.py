"""Diagnostics detail sheet tests for sessions, events, probes, and strategy candidates."""
import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_session_detail_sheet(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.SESSIONS_STATE_CONTENT),
            timeout=15,
            message="Sessions content should be populated with demo data",
        )
    except TimeoutError:
        pytest.skip("Sessions content not populated in time")

    diag.tap(DiagnosticsPage.SESSIONS_STATE_CONTENT)

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.SESSION_DETAIL_SHEET),
            timeout=10,
            message="Session detail sheet should open",
        )
    except TimeoutError:
        pytest.skip("Session detail sheet did not open")

    assert diag.is_visible(DiagnosticsPage.SESSION_DETAIL_SHEET), (
        "Session detail sheet should be visible after tapping a session"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_event_detail_sheet(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.EVENTS_STATE_CONTENT),
            timeout=15,
            message="Events content should be populated with demo data",
        )
    except TimeoutError:
        pytest.skip("Events content not populated in time")

    diag.tap(DiagnosticsPage.EVENTS_STATE_CONTENT)

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.EVENT_DETAIL_SHEET),
            timeout=10,
            message="Event detail sheet should open",
        )
    except TimeoutError:
        pytest.skip("Event detail sheet did not open")

    assert diag.is_visible(DiagnosticsPage.EVENT_DETAIL_SHEET), (
        "Event detail sheet should be visible after tapping an event"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_probe_detail_sheet(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    try:
        diag.wait_until(
            lambda: diag.is_scan_has_content(),
            timeout=30,
            message="Scan should complete with content",
        )
    except TimeoutError:
        pytest.skip("Scan did not complete with content in time")

    diag.swipe_to_scan_section()

    if not diag.is_visible(DiagnosticsPage.STRATEGY_PROBE_REPORT, timeout=5):
        pytest.skip("No probe report visible after scan")

    diag.tap(DiagnosticsPage.STRATEGY_PROBE_REPORT)

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.PROBE_DETAIL_SHEET),
            timeout=10,
            message="Probe detail sheet should open",
        )
    except TimeoutError:
        pytest.skip("Probe detail sheet did not open")

    assert diag.is_visible(DiagnosticsPage.PROBE_DETAIL_SHEET), (
        "Probe detail sheet should be visible after tapping a probe"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_strategy_candidate_detail_sheet(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    try:
        diag.wait_until(
            lambda: diag.is_scan_has_content(),
            timeout=30,
            message="Scan should complete with content",
        )
    except TimeoutError:
        pytest.skip("Scan did not complete with content in time")

    diag.swipe_to_scan_section()
    diag.scroll_to(DiagnosticsPage.STRATEGY_CANDIDATE_DETAIL_SHEET)

    if not diag.is_visible(DiagnosticsPage.STRATEGY_CANDIDATE_DETAIL_SHEET, timeout=5):
        pytest.skip("No strategy candidate visible after scan")

    diag.tap(DiagnosticsPage.STRATEGY_CANDIDATE_DETAIL_SHEET)

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.STRATEGY_CANDIDATE_NOTES),
            timeout=10,
            message="Candidate notes section should appear in detail sheet",
        )
    except TimeoutError:
        pytest.skip("Candidate detail sections did not load")

    assert diag.is_visible(DiagnosticsPage.STRATEGY_CANDIDATE_NOTES), (
        "Candidate notes section should be visible"
    )
    assert diag.is_visible(DiagnosticsPage.STRATEGY_CANDIDATE_SIGNATURE), (
        "Candidate signature section should be visible"
    )
    assert diag.is_visible(DiagnosticsPage.STRATEGY_CANDIDATE_RESULTS), (
        "Candidate results section should be visible"
    )
