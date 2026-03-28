"""Test 34: Connection state transitions -- toggle connect twice returns to idle."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="home",
    data_preset="clean_home",
    service_preset="idle",
)
def test_connection_toggle_returns_to_idle(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"

    # First tap: connect.
    home.tap_connect()
    home.wait_for(HomePage.CONNECTION_BUTTON, timeout=5)
    assert home.is_connection_button_visible(), (
        "Connection button should remain visible after first tap"
    )

    # Second tap: disconnect / return to idle.
    home.tap_connect()
    home.wait_for(HomePage.CONNECTION_BUTTON, timeout=5)
    assert home.is_connection_button_visible(), (
        "Connection button should remain visible after second tap"
    )
    assert home.is_loaded(), (
        "Home screen should still be loaded after toggling connection twice"
    )
