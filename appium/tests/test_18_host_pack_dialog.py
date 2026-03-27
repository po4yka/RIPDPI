"""Test 18: Host pack apply dialog -- dropdowns and confirm/dismiss."""

import pytest

from pages.base_page import BasePage


HOST_PACK_DIALOG = "host-pack-apply-dialog"
HOST_PACK_TARGET = "host-pack-target-dropdown"
HOST_PACK_APPLY_MODE = "host-pack-apply-mode-dropdown"
HOST_PACK_CONFIRM = "host-pack-apply-confirm"
HOST_PACK_DISMISS = "host-pack-apply-dismiss"


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_host_pack_confirm(driver):
    page = BasePage(driver)

    # Scroll to and open the host pack dialog.
    page.scroll_to(HOST_PACK_DIALOG)
    page.tap(HOST_PACK_DIALOG)

    assert page.is_visible(HOST_PACK_TARGET), "Target dropdown should be visible"
    assert page.is_visible(HOST_PACK_APPLY_MODE), "Apply mode dropdown should be visible"

    page.tap(HOST_PACK_CONFIRM)
    assert not page.is_visible(HOST_PACK_DIALOG, timeout=2), (
        "Dialog should dismiss after confirm"
    )


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_host_pack_dismiss(driver):
    page = BasePage(driver)

    page.scroll_to(HOST_PACK_DIALOG)
    page.tap(HOST_PACK_DIALOG)

    assert page.is_visible(HOST_PACK_TARGET), "Target dropdown should be visible"

    page.tap(HOST_PACK_DISMISS)
    assert not page.is_visible(HOST_PACK_DIALOG, timeout=2), (
        "Dialog should dismiss after tapping dismiss"
    )
