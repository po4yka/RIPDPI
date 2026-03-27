"""Test 01: Cold launch lands on the home screen (Maestro flow 01 parity)."""

import pytest

from pages.home_page import HomePage


@pytest.mark.automation(
    start_route="home",
    data_preset="clean_home",
    service_preset="idle",
)
def test_cold_launch_shows_home(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible after cold launch"
    assert home.is_connection_button_visible(), "Connection button should be visible"
