"""Test 09: Permission banner and VPN permission dialog handling."""

import pytest

from pages.home_page import HomePage
from pages.permission_dialog_page import PermissionDialogPage


@pytest.mark.automation(
    start_route="home",
    permission_preset="vpn_missing",
    data_preset="clean_home",
    service_preset="idle",
)
def test_vpn_dialog_continue(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_permission_banner_visible(), (
        "Permission issue banner should appear with vpn_missing preset"
    )

    home.tap_connect()

    dialog = PermissionDialogPage(driver)
    assert dialog.is_dialog_visible(), "VPN permission dialog should appear"

    dialog.tap_continue()
    assert not dialog.is_dialog_visible(), "Dialog should dismiss after continue"


@pytest.mark.automation(
    start_route="home",
    permission_preset="vpn_missing",
    data_preset="clean_home",
    service_preset="idle",
)
def test_vpn_dialog_dismiss(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"

    home.tap_connect()

    dialog = PermissionDialogPage(driver)
    assert dialog.is_dialog_visible(), "VPN permission dialog should appear"

    dialog.tap_dismiss()
    assert not dialog.is_dialog_visible(), "Dialog should dismiss after tapping dismiss"
    assert home.is_loaded(), "Should remain on home screen"
