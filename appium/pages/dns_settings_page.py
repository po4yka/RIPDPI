"""DNS settings screen page object."""

from __future__ import annotations

import time

from selenium.common.exceptions import NoSuchElementException, TimeoutException

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
    CUSTOM_DNSCRYPT_PROVIDER = "dns-custom-dnscrypt-provider"
    CUSTOM_DNSCRYPT_PUBLIC_KEY = "dns-custom-dnscrypt-public-key"
    CUSTOM_SAVE = "dns-custom-save"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def select_mode(self, mode: str) -> None:
        tag = f"dns-mode-{mode}"
        self.scroll_incrementally_to(tag).click()
        time.sleep(0.8)

    def select_protocol(self, protocol: str) -> None:
        tag = f"dns-protocol-{protocol}"
        self.scroll_incrementally_to(tag)
        if protocol == "dnscrypt":
            size = self.driver.get_window_size()
            center_x = int(size["width"] * 0.5)
            self.driver.swipe(
                center_x,
                int(size["height"] * 0.42),
                center_x,
                int(size["height"] * 0.62),
                duration=220,
            )
        self.wait_for(tag, timeout=5).click()
        time.sleep(0.8)
        expected_tag = {
            "doh": self.CUSTOM_DOH_URL,
            "dot": self.CUSTOM_HOST,
            "dnscrypt": self.CUSTOM_DNSCRYPT_PROVIDER,
        }.get(protocol)
        if expected_tag:
            self.scroll_incrementally_to(expected_tag)

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

    def set_dnscrypt_provider(self, provider: str) -> None:
        self.scroll_incrementally_to(self.CUSTOM_DNSCRYPT_PROVIDER)
        self.clear_and_type(self.CUSTOM_DNSCRYPT_PROVIDER, provider)

    def set_dnscrypt_public_key(self, public_key: str) -> None:
        self.scroll_incrementally_to(self.CUSTOM_DNSCRYPT_PUBLIC_KEY)
        self.clear_and_type(self.CUSTOM_DNSCRYPT_PUBLIC_KEY, public_key)

    def tap_custom_save(self) -> None:
        self.tap(self.CUSTOM_SAVE)

    def is_custom_save_visible(self) -> bool:
        try:
            self.scroll_incrementally_to(self.CUSTOM_SAVE)
            return True
        except (NoSuchElementException, TimeoutException):
            return False
