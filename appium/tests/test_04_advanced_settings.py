"""Test 04: Edit diagnostics retention days in advanced settings (Maestro flow 03 parity)."""

import pytest

from pages.advanced_settings_page import AdvancedSettingsPage


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
