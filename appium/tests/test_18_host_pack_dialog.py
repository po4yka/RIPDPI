"""Test 18: Host pack apply dialog -- dropdowns and confirm/dismiss."""

import pytest
from selenium.common.exceptions import TimeoutException

from pages.base_page import BasePage


HOST_PACK_DIALOG = "host-pack-apply-dialog"
HOST_PACK_YOUTUBE = "host-pack-preset-youtube"
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

    # Select a bundled host pack to open the apply dialog.
    try:
        page.scroll_incrementally_to(HOST_PACK_YOUTUBE, max_swipes=50)
    except TimeoutException:
        pytest.skip("Bundled host pack preset is not visible in this fixture")
    page.tap(HOST_PACK_YOUTUBE)

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

    try:
        page.scroll_incrementally_to(HOST_PACK_YOUTUBE, max_swipes=50)
    except TimeoutException:
        pytest.skip("Bundled host pack preset is not visible in this fixture")
    page.tap(HOST_PACK_YOUTUBE)

    assert page.is_visible(HOST_PACK_TARGET), "Target dropdown should be visible"

    page.tap(HOST_PACK_DISMISS)
    assert not page.is_visible(HOST_PACK_DIALOG, timeout=2), (
        "Dialog should dismiss after tapping dismiss"
    )
