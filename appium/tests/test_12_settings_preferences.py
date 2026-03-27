"""Test 12: Settings preferences -- WebRTC, theme, biometric, backup PIN."""

import pytest

from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_settings_preferences(driver):
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should be visible"

    # Toggle WebRTC protection.
    settings.tap_webrtc_toggle()

    # Open theme dropdown.
    settings.tap_theme_dropdown()
    # Dismiss by tapping elsewhere (back to settings context).
    settings.driver.back()

    # Biometric toggle should show confirmation dialog.
    settings.tap_biometric_toggle()
    assert settings.is_biometric_confirm_visible(), (
        "Biometric confirmation dialog should appear"
    )
    settings.dismiss_biometric_confirm()

    # Scroll to backup PIN field.
    settings.scroll_to(settings.BACKUP_PIN_FIELD)
    assert settings.is_backup_pin_field_visible(), (
        "Backup PIN field should be visible"
    )
