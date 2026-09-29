"""Test 34: Stop a connected proxy, then enable local DPI bypass again."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="home", data_preset="settings_ready", service_preset="connected_proxy",
)
def test_connection_toggle_returns_to_idle(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.get_local_bypass_action() == "Disable", "Connected proxy should start active"

    home.tap_local_bypass()
    home.wait_until(lambda: home.get_local_bypass_action() == "Enable")
    assert home.get_local_bypass_action() == "Enable", "Stopping the proxy should return to idle"

    home.tap_local_bypass()
    home.wait_until(lambda: home.get_local_bypass_action() == "Disable")
    assert home.get_local_bypass_action() == "Disable", "Local bypass should become active again"

    home.tap_local_bypass()
    home.wait_until(lambda: home.get_local_bypass_action() == "Enable")
    assert home.get_local_bypass_action() == "Enable", "Local bypass should end idle"
