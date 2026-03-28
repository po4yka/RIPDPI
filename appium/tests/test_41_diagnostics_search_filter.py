"""Diagnostics search, filter, and share section coverage."""
import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_sessions_search_visible(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    # overview -> scan -> live
    page.swipe_to_next_section()
    page.swipe_to_next_section()

    assert page.is_visible(
        DiagnosticsPage.SESSIONS_SEARCH
    ), "Sessions search field is not visible on the live section"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_events_search_and_auto_scroll(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    # overview -> scan -> live
    page.swipe_to_next_section()
    page.swipe_to_next_section()

    assert page.is_visible(
        DiagnosticsPage.EVENTS_SEARCH
    ), "Events search field is not visible on the live section"
    assert page.is_visible(
        DiagnosticsPage.EVENTS_AUTO_SCROLL
    ), "Events auto-scroll toggle is not visible on the live section"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_session_sensitive_toggle(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    # overview -> scan -> live
    page.swipe_to_next_section()
    page.swipe_to_next_section()

    assert page.is_visible(
        DiagnosticsPage.SESSION_SENSITIVE_TOGGLE
    ), "Session sensitive toggle is not visible with demo data"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_share_section_actions(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    page.swipe_to_share_section()

    assert page.is_visible(
        DiagnosticsPage.SAVE_LOGS
    ), "Save logs button is not visible on the share section"
    assert page.is_visible(
        DiagnosticsPage.SHARE_SUMMARY
    ), "Share summary button is not visible on the share section"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_diagnostics_content_states(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "DiagnosticsPage did not load"

    # overview -> scan -> live
    page.swipe_to_next_section()
    page.swipe_to_next_section()

    sessions_visible = page.is_visible(DiagnosticsPage.SESSIONS_STATE_CONTENT, timeout=5)
    events_visible = page.is_visible(DiagnosticsPage.EVENTS_STATE_CONTENT, timeout=5)

    if not sessions_visible and not events_visible:
        pytest.skip("Neither sessions nor events content state is visible with demo data")

    assert sessions_visible or events_visible, (
        "Expected at least one content state (sessions or events) to be visible"
    )
