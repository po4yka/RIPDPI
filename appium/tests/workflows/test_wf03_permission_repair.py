"""Workflow 03: Permission repair -- vpn_missing -> grant -> connect."""

import pytest

from pages.home_page import HomePage
from pages.permission_dialog_page import PermissionDialogPage


@pytest.mark.workflow
@pytest.mark.automation(
    start_route="home",
    permission_preset="vpn_missing",
    data_preset="clean_home",
    service_preset="idle",
)
def test_permission_repair_and_connect(workflow_app):
    driver = workflow_app

    # Step 1: Verify permission banner is shown.
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_permission_banner_visible(), (
        "Permission issue banner should appear with vpn_missing preset"
    )

    # Step 2: Tap connect, which triggers VPN permission dialog.
    home.tap_connect()

    dialog = PermissionDialogPage(driver)
    dialog.wait_for(dialog.DIALOG, timeout=10)
    assert dialog.is_dialog_visible(), "VPN permission dialog should appear"

    # Step 3: Grant permission by tapping continue.
    dialog.tap_continue()

    # Step 4: Wait for dialog to dismiss and connection to proceed.
    home.wait_until(
        lambda: not dialog.is_dialog_visible(),
        timeout=10,
        message="Dialog should dismiss after granting permission",
    )

    # Step 5: Verify connection button still visible (service started).
    home.wait_until(
        lambda: home.is_connection_button_visible(),
        timeout=15,
        message="Connection button should be visible after permission grant",
    )
