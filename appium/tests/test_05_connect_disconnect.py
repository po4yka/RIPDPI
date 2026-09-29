"""Test 05: Enable and disable local DPI bypass with the automation service."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(start_route="home", data_preset="settings_ready", service_preset="idle")
def test_connect_disconnect(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.get_local_bypass_action() == "Enable", "Local bypass should start idle"

    home.tap_local_bypass()
    home.wait_until(lambda: home.get_local_bypass_action() == "Disable")
    assert home.get_local_bypass_action() == "Disable", "Local bypass should become active"

    home.tap_local_bypass()
    home.wait_until(lambda: home.get_local_bypass_action() == "Enable")
    assert home.get_local_bypass_action() == "Enable", "Local bypass should return to idle"
