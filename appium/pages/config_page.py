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
        tag = f"config-preset-{preset_id}"
        self.scroll_incrementally_to(tag).click()

    def is_preset_visible(self, preset_id: str) -> bool:
        return self.is_visible(f"config-preset-{preset_id}")

    def tap_edit_current(self) -> None:
        self.scroll_incrementally_to(self.EDIT_CURRENT).click()

    def select_mode(self, mode: str) -> None:
        """Tap a mode chip (e.g. 'proxy', 'vpn')."""
        self.tap(f"config-mode-{mode}")

    def tap_dns_settings(self) -> None:
        self.scroll_incrementally_to(self.DNS_SETTINGS).click()
