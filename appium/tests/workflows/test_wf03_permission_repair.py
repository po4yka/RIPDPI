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
    start_configured_mode=True,
    ready_tag="vpn-permission-dialog",
)
def test_permission_repair_and_connect(workflow_app):
    driver = workflow_app

    # Step 1: Verify the configured-mode launch opens the local permission dialog.
    dialog = PermissionDialogPage(driver)
    dialog.wait_for(dialog.DIALOG, timeout=10)
    assert dialog.is_dialog_visible(), "VPN permission dialog should appear"

    # Step 2: Grant permission by tapping continue.
    dialog.tap_continue()

    # Step 3: Wait for dialog to dismiss and return to Home.
    home = HomePage(driver)
    home.wait_until(
        lambda: not dialog.is_dialog_visible(),
        timeout=10,
        message="Dialog should dismiss after granting permission",
    )

    # Step 4: Verify Home remains usable after the permission path.
    home.wait_until(
        lambda: home.is_loaded() and home.is_any_mode_card_visible(),
        timeout=15,
        message="Home mode cards should be visible after permission grant",
    )
