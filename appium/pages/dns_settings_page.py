"""DNS settings screen page object."""

from __future__ import annotations

import time

from .base_page import BasePage


class DnsSettingsPage(BasePage):
    SCREEN = "dns_settings-screen"
    PLAIN_ADDRESS = "dns-plain-address"
    PLAIN_SAVE = "dns-plain-save"
    CUSTOM_DOH_URL = "dns-custom-doh-url"
    CUSTOM_HOST = "dns-custom-host"
    CUSTOM_PORT = "dns-custom-port"
    CUSTOM_TLS_SERVER_NAME = "dns-custom-tls-server-name"
    CUSTOM_BOOTSTRAP = "dns-custom-bootstrap"
    CUSTOM_SAVE = "dns-custom-save"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def select_mode(self, mode: str) -> None:
        tag = f"dns-mode-{mode}"
        self.scroll_incrementally_to(tag).click()
        time.sleep(0.8)

    def select_protocol(self, protocol: str) -> None:
        tag = f"dns-protocol-{protocol}"
        self.scroll_incrementally_to(tag).click()
        time.sleep(0.8)

    def set_plain_address(self, ip: str) -> None:
        self.scroll_incrementally_to(self.PLAIN_ADDRESS)
        self.clear_and_type(self.PLAIN_ADDRESS, ip)

    def tap_plain_save(self) -> None:
        self.scroll_incrementally_to(self.PLAIN_SAVE).click()

    def is_plain_save_visible(self) -> bool:
        return self.is_visible(self.PLAIN_SAVE)

    def get_plain_address(self) -> str:
        self.scroll_incrementally_to(self.PLAIN_ADDRESS)
        return self.get_text(self.PLAIN_ADDRESS)

    def select_doh_resolver(self, provider: str) -> None:
        tag = f"dns-resolver-{provider}"
        self.scroll_incrementally_to(tag).click()

    def set_custom_doh_url(self, url: str) -> None:
        self.scroll_incrementally_to(self.CUSTOM_DOH_URL)
        self.clear_and_type(self.CUSTOM_DOH_URL, url)

    def set_dot_host(self, host: str) -> None:
        self.scroll_incrementally_to(self.CUSTOM_HOST)
        self.clear_and_type(self.CUSTOM_HOST, host)

    def set_dot_port(self, port: str) -> None:
        self.scroll_incrementally_to(self.CUSTOM_PORT)
        self.clear_and_type(self.CUSTOM_PORT, port)

    def set_tls_server_name(self, name: str) -> None:
        self.scroll_incrementally_to(self.CUSTOM_TLS_SERVER_NAME)
        self.clear_and_type(self.CUSTOM_TLS_SERVER_NAME, name)

    def tap_custom_save(self) -> None:
        self.tap(self.CUSTOM_SAVE)

    def is_custom_save_visible(self) -> bool:
        return self.is_visible(self.CUSTOM_SAVE)
