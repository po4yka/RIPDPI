"""Test 05: Start and stop configured mode (Maestro flow 04 parity)."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="home",
    data_preset="clean_home",
    service_preset="idle",
)
def test_connect_disconnect(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"

    # Tap connect.
    home.tap_connect()
    assert home.is_connection_button_visible(), (
        "Connection button should remain visible after connecting"
    )

    # Tap again to disconnect.
    home.tap_connect()
    assert home.is_connection_button_visible(), (
        "Connection button should remain visible after disconnecting"
    )
