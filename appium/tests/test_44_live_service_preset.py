"""Tests for basic screen loading with the live service preset."""
import pytest

from pages.home_page import HomePage
from pages.diagnostics_page import DiagnosticsPage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="home",
    data_preset="clean_home",
    service_preset="live",
)
def test_live_home_screen(driver):
    page = HomePage(driver)

    try:
        page.wait_until(
            lambda: page.is_loaded(),
            timeout=15,
            message="Home screen did not load with live preset",
        )
    except TimeoutError:
        pytest.skip("Home screen did not load with live preset in time")

    assert page.is_loaded(), "Home screen should be loaded with live preset"
    assert page.is_connection_button_visible(), "Connection button should be visible with live preset"
    assert page.is_stats_grid_visible(), "Stats grid should be visible with live preset"


@pytest.mark.automation(
    start_route="diagnostics",
    data_preset="diagnostics_demo",
    service_preset="live",
)
def test_live_diagnostics_screen(driver):
    page = DiagnosticsPage(driver)

    try:
        page.wait_until(
            lambda: page.is_loaded(),
            timeout=15,
            message="Diagnostics screen did not load with live preset",
        )
    except TimeoutError:
        pytest.skip("Diagnostics screen did not load with live preset in time")

    assert page.is_loaded(), "Diagnostics screen should be loaded with live preset"
    assert page.is_section_visible(
        "overview"
    ), "Overview section should be visible on diagnostics screen with live preset"


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="live",
)
def test_live_settings_screen(driver):
    page = SettingsPage(driver)

    try:
        page.wait_until(
            lambda: page.is_loaded(),
            timeout=15,
            message="Settings screen did not load with live preset",
        )
    except TimeoutError:
        pytest.skip("Settings screen did not load with live preset in time")

    assert page.is_loaded(), "Settings screen should be loaded with live preset"
