"""Test 03: Navigate from settings to advanced settings (Maestro flow 02 parity)."""

import pytest

from pages.advanced_settings_page import AdvancedSettingsPage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_settings_to_advanced(driver):
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should be visible"

    settings.tap_advanced_settings()

    advanced = AdvancedSettingsPage(driver)
    assert advanced.is_loaded(), "Advanced settings screen should appear after tap"
