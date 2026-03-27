"""Test 22: Home screen in connected proxy state (vs VPN in test 11)."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="home",
    service_preset="connected_proxy",
    data_preset="clean_home",
)
def test_connected_proxy_state(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_connection_button_visible(), "Connection button should be visible"
    assert home.is_stats_grid_visible(), (
        "Stats grid should be visible when proxy is connected"
    )
