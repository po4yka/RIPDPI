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
    initial_webrtc = settings.is_webrtc_enabled()
    settings.tap_webrtc_toggle()
    settings.wait_until(lambda: settings.is_webrtc_enabled() != initial_webrtc)
    assert settings.is_webrtc_enabled() != initial_webrtc, (
        "WebRTC protection should change after tapping the toggle"
    )

    # Open theme dropdown.
    settings.tap_theme_dropdown()
    # Dismiss by tapping elsewhere (back to settings context).
    settings.driver.back()

    # Biometric unlock requires enrolled biometrics and a local fallback PIN.
    if settings.is_biometric_available():
        settings.tap_biometric_toggle()
        assert settings.is_biometric_pin_required_visible(), (
            "Backup PIN requirement dialog should appear before biometric enablement"
        )
        settings.dismiss_biometric_pin_required()
        assert not settings.is_biometric_pin_required_visible(), "PIN requirement dialog should close"
    assert not settings.is_biometric_enabled(), "Biometric unlock must remain off without a backup PIN"
