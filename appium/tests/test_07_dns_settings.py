"""Test 07: Enter a plain DNS address and save."""

import pytest

from pages.bottom_nav import BottomNav
from pages.dns_settings_page import DnsSettingsPage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_plain_dns_save(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings screen should be visible"

    dns.select_mode("plain-udp")
    dns.set_plain_address("1.0.0.1")
    assert dns.is_plain_save_visible(), "Plain save button should be visible after entering address"

    assert dns.is_plain_save_enabled(), "Save should be enabled for the changed DNS address"
    dns.tap_plain_save()
    dns.wait_until(lambda: not dns.is_plain_save_enabled())
    assert not dns.is_plain_save_enabled(), "Save should be disabled after the address is stored"
    BottomNav(driver).navigate_to("settings")
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should appear after leaving DNS settings"
    settings.tap_dns_settings()
    assert dns.is_loaded(), "DNS settings should reopen"
    assert dns.is_mode_selected("plain-udp"), "Plain DNS mode should remain selected"
    assert dns.get_plain_address() == "1.0.0.1", "Saved DNS address should be exactly 1.0.0.1"
