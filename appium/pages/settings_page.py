"""Settings screen page object."""

from __future__ import annotations

from .base_page import BasePage


class SettingsPage(BasePage):
    SCREEN = "settings-screen"
    ADVANCED_SETTINGS = "settings-advanced-settings"
    DNS_SETTINGS = "settings-dns-settings"
    WEBRTC_PROTECTION = "settings-webrtc-protection"
    ABOUT = "settings-about"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_advanced_settings(self) -> None:
        self.tap(self.ADVANCED_SETTINGS)

    def tap_dns_settings(self) -> None:
        self.tap(self.DNS_SETTINGS)
