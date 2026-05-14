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

    # Biometric unlock requires a local fallback PIN before it can be enabled.
    settings.tap_biometric_toggle()
    assert settings.is_biometric_pin_required_visible(), (
        "Backup PIN requirement dialog should appear before biometric enablement"
    )
    settings.dismiss_biometric_pin_required()

    # Scroll to backup PIN field.
    settings.scroll_down_to(settings.BACKUP_PIN_FIELD, max_swipes=12)
    assert settings.is_backup_pin_field_visible(), (
        "Backup PIN field should be visible"
    )
