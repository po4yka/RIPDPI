"""Test 26: Config mode chips (VPN vs Proxy)."""

import pytest

from pages.config_page import ConfigPage


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
