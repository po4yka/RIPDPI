"""Test 07: Enter a plain DNS address and save."""

import pytest

from pages.dns_settings_page import DnsSettingsPage


@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_plain_dns_save(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings screen should be visible"

    dns.set_plain_address("1.1.1.1")
    assert dns.is_plain_save_visible(), "Plain save button should be visible after entering address"

    dns.tap_plain_save()
