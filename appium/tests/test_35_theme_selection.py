"""Test 35: Theme selection -- pick dark theme from settings dropdown."""

import pytest

from pages.base_page import BasePage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_select_dark_theme(driver):
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should be visible"

    # Open theme dropdown.
    settings.tap_theme_dropdown()

    # Select the dark theme option.
    base = BasePage(driver)
    base.tap("settings-theme-dropdown-option-dark")

    assert settings.is_loaded(), (
        "Settings screen should remain visible after selecting dark theme"
    )
