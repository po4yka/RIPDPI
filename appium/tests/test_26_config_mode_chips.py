"""Test 26: Config mode chips (VPN vs Proxy)."""

import pytest

from pages.bottom_nav import BottomNav
from pages.config_page import ConfigPage
from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="config",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_config_mode_chips(driver):
    config = ConfigPage(driver)
    assert config.is_loaded(), "Config screen should be visible"

    # Tap proxy mode chip.
    config.select_mode("proxy")
    config.wait_until(lambda: config.is_selected("config-mode-proxy"))
    assert config.is_selected("config-mode-proxy"), "Proxy mode should be selected"
    assert not config.is_selected("config-mode-vpn"), "VPN mode should be deselected"

    # Tap VPN mode chip.
    config.select_mode("vpn")
    config.wait_until(lambda: config.is_selected("config-mode-vpn"))
    assert config.is_selected("config-mode-vpn"), "VPN mode should be selected"
    assert not config.is_selected("config-mode-proxy"), "Proxy mode should be deselected"

    # Screen should remain on config after mode switching.
    assert config.is_loaded(), "Config screen should remain visible after mode switches"


@pytest.mark.automation(
    start_route="config",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_config_runtime_action_uses_saved_mode_and_stops_running_mode(driver):
    config = ConfigPage(driver)
    config.select_mode("proxy")
    config.wait_until(lambda: config.is_selected("config-mode-proxy"))
    config.open_server_settings()
    assert config.is_selected("config-mode-proxy")
    assert config.runtime_action_label() == "Connect"

    config.tap_runtime_action()
    config.wait_until(lambda: config.runtime_action_label() == "Disconnect")
    nav = BottomNav(driver)
    nav.navigate_to("home")
    home = HomePage(driver)
    home.wait_until(lambda: home.get_local_bypass_action() == "Disable")
    home.scroll_incrementally_to(home.CONNECTION_BUTTON)
    assert home.get_text(home.CONNECTION_BUTTON) == "Enable"
    nav.navigate_to("config")
    config.wait_for_screen(config.SCREEN)
    config.select_mode("vpn")
    config.wait_until(lambda: config.is_selected("config-mode-vpn"))
    assert config.runtime_action_label() == "Disconnect"

    config.tap_runtime_action()
    config.wait_until(lambda: config.runtime_action_label() == "Connect")
    config.select_mode("vpn")
    assert config.is_selected("config-mode-vpn")
