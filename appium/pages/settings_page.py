"""Settings screen page object."""

from __future__ import annotations

from .base_page import BasePage


class SettingsPage(BasePage):
    SCREEN = "settings-screen"
    ADVANCED_SETTINGS = "settings-advanced-settings"
    DNS_SETTINGS = "settings-dns-settings"
    WEBRTC_PROTECTION = "settings-webrtc-protection"
    THEME_DROPDOWN = "settings-theme-dropdown"
    BIOMETRIC = "settings-biometric"
    BIOMETRIC_CONFIRM_DIALOG = "settings-biometric-confirm-dialog"
    BIOMETRIC_CONFIRM_ENABLE = "settings-biometric-confirm-enable"
    BIOMETRIC_CONFIRM_CANCEL = "settings-biometric-confirm-cancel"
    BACKUP_PIN_FIELD = "settings-backup-pin-field"
    BACKUP_PIN_SAVE = "settings-backup-pin-save"
    ABOUT = "settings-about"
    BACKUP_PIN_CLEAR = "settings-backup-pin-clear"
    BACKUP_PIN_WARNING = "settings-backup-pin-warning"
    CUSTOMIZATION = "settings-customization"
    BACKGROUND_GUIDANCE_BANNER = "settings-background-guidance-banner"
    SUPPORT_BUNDLE = "settings-support-bundle"
    DATA_TRANSPARENCY = "settings-data-transparency"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_advanced_settings(self) -> None:
        self.tap(self.ADVANCED_SETTINGS)

    def tap_dns_settings(self) -> None:
        self.tap(self.DNS_SETTINGS)

    def tap_webrtc_toggle(self) -> None:
        self.tap(self.WEBRTC_PROTECTION)

    def tap_theme_dropdown(self) -> None:
        self.tap(self.THEME_DROPDOWN)

    def select_theme_option(self, option: str) -> None:
        """Select a theme from the dropdown (e.g. 'dark', 'light', 'system')."""
        self.tap(f"settings-theme-dropdown-option-{option}")

    def tap_biometric_toggle(self) -> None:
        self.scroll_to(self.BIOMETRIC)
        self.tap(self.BIOMETRIC)

    def is_biometric_confirm_visible(self) -> bool:
        return self.is_visible(self.BIOMETRIC_CONFIRM_DIALOG)

    def dismiss_biometric_confirm(self) -> None:
        self.tap(self.BIOMETRIC_CONFIRM_CANCEL)

    def is_backup_pin_field_visible(self) -> bool:
        return self.is_visible(self.BACKUP_PIN_FIELD)

    def is_background_guidance_visible(self) -> bool:
        return self.is_visible(self.BACKGROUND_GUIDANCE_BANNER)

    def tap_customization(self) -> None:
        self.scroll_to(self.CUSTOMIZATION)
        self.tap(self.CUSTOMIZATION)
