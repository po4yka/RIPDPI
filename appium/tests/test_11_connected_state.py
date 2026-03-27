"""Test 11: Home screen in connected VPN state."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="home",
    service_preset="connected_vpn",
    data_preset="clean_home",
)
def test_connected_vpn_state(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_connection_button_visible(), "Connection button should be visible"
    assert home.is_stats_grid_visible(), (
        "Stats grid should be visible when VPN is connected"
    )
    assert home.is_approach_card_visible(), (
        "Approach card should be visible when connected"
    )
