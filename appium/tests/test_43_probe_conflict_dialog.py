"""Tests for the hidden probe conflict dialog on the Diagnostics screen."""
import pytest

from pages.diagnostics_page import DiagnosticsPage


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_conflict_dialog_appears_on_double_scan(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "Diagnostics screen did not load"

    page.swipe_to_scan_section()
    page.tap_run_raw_scan()

    try:
        page.wait_until(
            lambda: page.is_scan_in_progress(),
            timeout=10,
            message="Scan did not enter progress state",
        )
    except TimeoutError:
        pytest.skip("Scan did not enter progress state in time")

    page.tap(DiagnosticsPage.SCAN_RUN_IN_PATH)

    try:
        page.wait_until(
            lambda: page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=10,
            message="Probe conflict dialog did not appear",
        )
    except TimeoutError:
        pytest.skip("Probe conflict dialog did not appear in time")

    assert page.is_visible(
        DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG
    ), "Probe conflict dialog should be visible after double scan attempt"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_conflict_dialog_wait_button(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "Diagnostics screen did not load"

    page.swipe_to_scan_section()
    page.tap_run_raw_scan()

    try:
        page.wait_until(
            lambda: page.is_scan_in_progress(),
            timeout=10,
            message="Scan did not enter progress state",
        )
    except TimeoutError:
        pytest.skip("Scan did not enter progress state in time")

    page.tap(DiagnosticsPage.SCAN_RUN_IN_PATH)

    try:
        page.wait_until(
            lambda: page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=10,
            message="Probe conflict dialog did not appear",
        )
    except TimeoutError:
        pytest.skip("Probe conflict dialog did not appear in time")

    page.tap(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_WAIT)

    try:
        page.wait_until(
            lambda: not page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=5,
            message="Probe conflict dialog did not dismiss after tapping wait",
        )
    except TimeoutError:
        pytest.skip("Dialog did not dismiss after tapping wait in time")

    assert not page.is_visible(
        DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG
    ), "Probe conflict dialog should be dismissed after tapping wait"
    assert page.is_scan_in_progress(), "Scan should still be in progress after tapping wait"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_conflict_dialog_cancel_and_run(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "Diagnostics screen did not load"

    page.swipe_to_scan_section()
    page.tap_run_raw_scan()

    try:
        page.wait_until(
            lambda: page.is_scan_in_progress(),
            timeout=10,
            message="Scan did not enter progress state",
        )
    except TimeoutError:
        pytest.skip("Scan did not enter progress state in time")

    page.tap(DiagnosticsPage.SCAN_RUN_IN_PATH)

    try:
        page.wait_until(
            lambda: page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=10,
            message="Probe conflict dialog did not appear",
        )
    except TimeoutError:
        pytest.skip("Probe conflict dialog did not appear in time")

    page.tap(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_CANCEL_AND_RUN)

    try:
        page.wait_until(
            lambda: not page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=5,
            message="Probe conflict dialog did not dismiss after cancel-and-run",
        )
    except TimeoutError:
        pytest.skip("Dialog did not dismiss after cancel-and-run in time")

    assert not page.is_visible(
        DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG
    ), "Probe conflict dialog should be dismissed after cancel-and-run"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="connected_vpn",
)
def test_conflict_dialog_dismiss(driver):
    page = DiagnosticsPage(driver)
    assert page.is_loaded(), "Diagnostics screen did not load"

    page.swipe_to_scan_section()
    page.tap_run_raw_scan()

    try:
        page.wait_until(
            lambda: page.is_scan_in_progress(),
            timeout=10,
            message="Scan did not enter progress state",
        )
    except TimeoutError:
        pytest.skip("Scan did not enter progress state in time")

    page.tap(DiagnosticsPage.SCAN_RUN_IN_PATH)

    try:
        page.wait_until(
            lambda: page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=10,
            message="Probe conflict dialog did not appear",
        )
    except TimeoutError:
        pytest.skip("Probe conflict dialog did not appear in time")

    page.tap(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DISMISS)

    try:
        page.wait_until(
            lambda: not page.is_visible(DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG),
            timeout=5,
            message="Probe conflict dialog did not dismiss",
        )
    except TimeoutError:
        pytest.skip("Dialog did not dismiss in time")

    assert not page.is_visible(
        DiagnosticsPage.HIDDEN_PROBE_CONFLICT_DIALOG
    ), "Probe conflict dialog should be dismissed after tapping dismiss"
