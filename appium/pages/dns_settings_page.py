"""DNS settings screen page object."""

from __future__ import annotations

from .base_page import BasePage


class DnsSettingsPage(BasePage):
    SCREEN = "dns_settings-screen"
    PLAIN_ADDRESS = "dns-plain-address"
    PLAIN_SAVE = "dns-plain-save"
    CUSTOM_DOH_URL = "dns-custom-doh-url"
    CUSTOM_SAVE = "dns-custom-save"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def set_plain_address(self, ip: str) -> None:
        self.clear_and_type(self.PLAIN_ADDRESS, ip)

    def tap_plain_save(self) -> None:
        self.tap(self.PLAIN_SAVE)

    def is_plain_save_visible(self) -> bool:
        return self.is_visible(self.PLAIN_SAVE)
