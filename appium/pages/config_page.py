"""Config screen page object."""

from __future__ import annotations

from .base_page import BasePage


class ConfigPage(BasePage):
    SCREEN = "config-screen"
    EDIT_CURRENT = "config-edit-current"
    DNS_SETTINGS = "config-dns-settings"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def select_preset(self, preset_id: str) -> None:
        self.tap(f"config-preset-{preset_id}")

    def is_preset_visible(self, preset_id: str) -> bool:
        return self.is_visible(f"config-preset-{preset_id}")

    def tap_edit_current(self) -> None:
        self.tap(self.EDIT_CURRENT)

    def select_mode(self, mode: str) -> None:
        """Tap a mode chip (e.g. 'proxy', 'vpn')."""
        self.tap(f"config-mode-{mode}")

    def tap_dns_settings(self) -> None:
        self.tap(self.DNS_SETTINGS)
