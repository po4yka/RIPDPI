"""Test 23: Permission banners on settings and home with various presets."""

import pytest

from pages.home_page import HomePage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="home",
    permission_preset="notifications_missing",
    data_preset="clean_home",
    service_preset="idle",
)
def test_notifications_missing_banner(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_permission_banner_visible(), (
        "Permission issue banner should appear with notifications_missing preset"
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
    assert settings.is_visible("settings-permission-battery-optimization"), (
        "Battery optimization permission card should appear with battery_review preset"
    )


@pytest.mark.automation(
    start_route="home",
    permission_preset="notifications_missing",
    data_preset="clean_home",
    service_preset="idle",
)
def test_banner_dismiss(driver):
    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should be visible"
    assert home.is_permission_banner_visible(), "Permission banner should be visible"

    home.tap("warning-banner-dismiss")
    assert not home.is_visible("home-permission-issue-banner", timeout=2), (
        "Banner should disappear after dismiss"
    )
