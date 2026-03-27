"""Workflow 02: Config -> Select preset -> Home -> Connect -> Verify."""

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

    # Step 1: Select a preset on config screen.
    config = ConfigPage(driver)
    assert config.is_loaded(), "Config screen should be visible"
    config.select_preset("medium")

    # Step 2: Navigate to home via bottom nav.
    nav = BottomNav(driver)
    nav.navigate_to("home")

    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)
    assert home.is_loaded(), "Home screen should appear after nav"

    # Step 3: Tap connect.
    home.tap_connect()

    # Step 4: Verify connected state.
    home.wait_until(
        lambda: home.is_connection_button_visible(),
        timeout=15,
        message="Connection button should remain visible after connect",
    )
