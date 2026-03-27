"""Test 25: DNSCrypt protocol fields."""

import pytest

from pages.dns_settings_page import DnsSettingsPage


@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_dnscrypt_fields(driver):
    dns = DnsSettingsPage(driver)
    assert dns.is_loaded(), "DNS settings screen should be visible"

    # Fill DNSCrypt-specific fields.
    dns.scroll_to("dns-custom-dnscrypt-provider")
    dns.clear_and_type("dns-custom-dnscrypt-provider", "2.dnscrypt-cert.example.com")

    dns.scroll_to("dns-custom-dnscrypt-public-key")
    dns.clear_and_type(
        "dns-custom-dnscrypt-public-key",
        "A" * 64,  # 64-char hex placeholder
    )

    assert dns.is_custom_save_visible(), (
        "Save button should be visible after filling DNSCrypt fields"
    )
