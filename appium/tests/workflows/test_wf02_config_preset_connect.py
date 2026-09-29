"""Workflow 02: Select Proxy mode, enable local DPI bypass, then stop it."""

import pytest

from pages.bottom_nav import BottomNav
from pages.config_page import ConfigPage
from pages.home_page import HomePage


@pytest.mark.workflow
@pytest.mark.automation(
    start_route="config",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_config_preset_then_connect(workflow_app):
    driver = workflow_app

    # Step 1: Select Proxy mode on the config screen.
    config = ConfigPage(driver)
    assert config.is_loaded(), "Config screen should be visible"
    config.select_mode("proxy")
    config.wait_until(lambda: config.is_selected("config-mode-proxy"))
    assert config.is_selected("config-mode-proxy"), "Proxy mode should be selected"

    # Step 2: Navigate to home via bottom nav.
    nav = BottomNav(driver)
    nav.navigate_to("home")

    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)
    assert home.is_loaded(), "Home screen should appear after nav"

    # Step 3: Tap the configured primary action.
    assert home.get_local_bypass_action() == "Enable", "Local bypass should start idle"
    home.tap_local_bypass()

    # Step 4: Verify connected state.
    home.wait_until(
        lambda: home.get_local_bypass_action() == "Disable",
        timeout=15,
        message="Local bypass should become active after the primary action",
    )
    assert home.get_local_bypass_action() == "Disable", "Selected proxy mode should become active"
    home.tap_local_bypass()
    home.wait_until(lambda: home.get_local_bypass_action() == "Enable")
    assert home.get_local_bypass_action() == "Enable", "Local bypass should stop after disconnect"
