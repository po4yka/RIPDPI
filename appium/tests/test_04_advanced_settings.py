"""Test 04: Edit diagnostics retention days in advanced settings (Maestro flow 03 parity)."""

import pytest

from pages.advanced_settings_page import AdvancedSettingsPage
from pages.bottom_nav import BottomNav
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_edit_retention_days(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced settings screen should be visible"

    page.edit_retention_days("21")

    assert page.is_retention_save_visible(), "Save button should be visible after editing"
    assert page.is_retention_save_enabled(), "Save should be enabled for changed retention days"
    page.tap_retention_save()
    page.wait_until(lambda: not page.is_retention_save_enabled())
    assert not page.is_retention_save_enabled(), "Save should be disabled after retention is stored"
    BottomNav(driver).navigate_to("settings")
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should appear after leaving advanced settings"
    settings.tap_advanced_settings()
    assert page.is_loaded(), "Advanced settings should reopen"
    assert page.get_retention_days() == "21", "Retention must persist as exactly 21 days"
