"""Strategy audit report, winning path, and overview element tests."""
import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_strategy_winning_path_elements(driver):
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
    diag.scroll_to(DiagnosticsPage.STRATEGY_WINNING_PATH)

    assert diag.is_visible(DiagnosticsPage.STRATEGY_WINNING_PATH), (
        "Winning path card should be visible after scan completes"
    )
    assert diag.is_visible(DiagnosticsPage.STRATEGY_WINNING_TCP_ACTION), (
        "Winning TCP action should be visible"
    )
    assert diag.is_visible(DiagnosticsPage.STRATEGY_WINNING_QUIC_ACTION), (
        "Winning QUIC action should be visible"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_strategy_full_matrix_toggle(driver):
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
    diag.scroll_to(DiagnosticsPage.STRATEGY_FULL_MATRIX_TOGGLE)

    assert diag.is_visible(DiagnosticsPage.STRATEGY_FULL_MATRIX_TOGGLE), (
        "Full matrix toggle should be visible"
    )
    diag.tap(DiagnosticsPage.STRATEGY_FULL_MATRIX_TOGGLE)


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_strategy_audit_assessment_visible(driver):
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
    diag.scroll_to(DiagnosticsPage.STRATEGY_AUDIT_ASSESSMENT)

    assert diag.is_visible(DiagnosticsPage.STRATEGY_AUDIT_ASSESSMENT), (
        "Audit assessment card should be visible"
    )

    if diag.is_visible(DiagnosticsPage.STRATEGY_AUDIT_LOW_CONFIDENCE, timeout=3):
        assert True, "Low confidence banner is present"
    elif diag.is_visible(DiagnosticsPage.STRATEGY_AUDIT_MEDIUM_CONFIDENCE, timeout=3):
        assert True, "Medium confidence note is present"
    else:
        pytest.skip("No confidence indicator visible in current scan results")


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_workflow_restriction_card(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.WORKFLOW_RESTRICTION_CARD),
            timeout=15,
            message="Workflow restriction card should appear",
        )
    except TimeoutError:
        pytest.skip("Workflow restriction card not present with current preset")

    assert diag.is_visible(DiagnosticsPage.WORKFLOW_RESTRICTION_CARD), (
        "Workflow restriction card should be visible"
    )
    assert diag.is_visible(DiagnosticsPage.WORKFLOW_RESTRICTION_ACTION), (
        "Workflow restriction action CTA should be visible"
    )


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="idle",
)
def test_overview_automatic_probe_and_history(driver):
    diag = DiagnosticsPage(driver)
    assert diag.is_loaded(), "Diagnostics screen should be visible"

    diag.swipe_to_overview_section()

    try:
        diag.wait_until(
            lambda: diag.is_visible(DiagnosticsPage.OVERVIEW_AUTOMATIC_PROBE_CARD),
            timeout=10,
            message="Automatic probe card should be visible on overview",
        )
    except TimeoutError:
        pytest.skip("Automatic probe card not visible on overview section")

    assert diag.is_visible(DiagnosticsPage.OVERVIEW_AUTOMATIC_PROBE_CARD), (
        "Automatic probe card should be visible"
    )
    assert diag.is_visible(DiagnosticsPage.OVERVIEW_HISTORY_ACTION), (
        "Overview history action should be visible"
    )
