"""Config screen page object."""

from __future__ import annotations

from .base_page import BasePage


class ConfigPage(BasePage):
    SCREEN = "config-screen"
    CUSTOM_PRESET = "config-preset-custom"
    DNS_SETTINGS = "config-dns-settings"
    PROFILE_CHANGE = "config-profile-change"
    RUNTIME_ACTION = "config-runtime-action"
    SERVER_SETTINGS = "config-mode-section-vpn"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def open_profile_choices(self) -> None:
        self.scroll_incrementally_to(self.PROFILE_CHANGE).click()

    def select_preset(self, preset_id: str) -> None:
        tag = f"config-preset-{preset_id}"
        self.scroll_incrementally_to(tag).click()

    def is_preset_visible(self, preset_id: str) -> bool:
        return self.is_visible(f"config-preset-{preset_id}")

    def open_custom_editor(self) -> None:
        self.open_profile_choices()
        self.scroll_incrementally_to(self.CUSTOM_PRESET).click()

    def select_mode(self, mode: str) -> None:
        """Tap a mode chip (e.g. 'proxy', 'vpn')."""
        self.scroll_incrementally_to(f"config-mode-{mode}").click()

    def tap_dns_settings(self) -> None:
        self.scroll_incrementally_to(self.DNS_SETTINGS).click()

    def open_server_settings(self) -> None:
        self.scroll_incrementally_to(self.SERVER_SETTINGS).click()

    def tap_runtime_action(self) -> None:
        self.scroll_incrementally_to(self.RUNTIME_ACTION).click()

    def runtime_action_label(self) -> str:
        self.scroll_incrementally_to(self.RUNTIME_ACTION)
        return self.get_text(self.RUNTIME_ACTION)
