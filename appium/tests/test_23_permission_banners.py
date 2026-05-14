"""Test 23: Permission banners on settings and home with various presets."""

import pytest

from pages.home_page import HomePage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="home",
    permission_preset="battery_review",
    data_preset="clean_home",
    service_preset="idle",
)
def test_battery_review_home_recommendation_banner(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_permission_recommendation_visible(), (
        "Permission recommendation banner should appear with battery_review preset"
    )


@pytest.mark.automation(
    start_route="settings",
    permission_preset="battery_review",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_battery_review_permission_card(driver):
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should be visible"
    settings.scroll_incrementally_to("settings-permission-battery-optimization")
    assert settings.is_visible("settings-permission-battery-optimization"), (
        "Battery optimization permission card should appear with battery_review preset"
    )


@pytest.mark.automation(
    start_route="home",
    permission_preset="battery_review",
    data_preset="clean_home",
    service_preset="idle",
)
def test_banner_dismiss(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_any_permission_guidance_visible(), "Permission guidance banner should be visible"

    home.dismiss_warning_banner()
    assert not home.is_any_permission_guidance_visible(), (
        "Permission guidance banner should disappear after dismiss"
    )
