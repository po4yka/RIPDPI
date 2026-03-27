"""Test 13: Encrypted DNS settings -- DoH resolver and DoT fields."""

import pytest

from pages.dns_settings_page import DnsSettingsPage


@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_doh_resolver_selection(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings screen should be visible"

    dns.select_doh_resolver("doh-cloudflare")
    assert dns.is_custom_save_visible(), (
        "Save button should be visible after selecting DoH resolver"
    )


@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_custom_doh_url(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings screen should be visible"

    dns.set_custom_doh_url("https://dns.example.com/dns-query")
    assert dns.is_custom_save_visible(), (
        "Save button should be visible after entering custom DoH URL"
    )


@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_dot_fields(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings screen should be visible"

    dns.set_dot_host("dns.example.com")
    dns.set_dot_port("853")
    dns.set_tls_server_name("dns.example.com")
    assert dns.is_custom_save_visible(), (
        "Save button should be visible after filling DoT fields"
    )
