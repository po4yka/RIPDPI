"""Workflow 04: DNS settings round-trip -- set address, navigate away, return, verify persisted."""

import pytest

from pages.bottom_nav import BottomNav
from pages.dns_settings_page import DnsSettingsPage
from pages.home_page import HomePage
from pages.settings_page import SettingsPage


@pytest.mark.workflow
@pytest.mark.automation(
    start_route="home",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_dns_settings_persist_across_navigation(workflow_app):
    driver = workflow_app
    nav = BottomNav(driver)

    # Step 1: Navigate to settings.
    nav.navigate_to("settings")
    settings = SettingsPage(driver)
    settings.wait_for_screen(SettingsPage.SCREEN)

    # Step 2: Open DNS settings.
    settings.tap_dns_settings()
    dns = DnsSettingsPage(driver)
    dns.wait_for_screen(DnsSettingsPage.SCREEN)

    # Step 3: Set plain DNS address.
    dns.set_plain_address("9.9.9.9")
    dns.tap_plain_save()

    # Step 4: Navigate back to home.
    driver.back()
    settings.wait_for_screen(SettingsPage.SCREEN)

    nav.navigate_to("home")
    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)

    # Step 5: Navigate back to settings -> DNS.
    nav.navigate_to("settings")
    settings.wait_for_screen(SettingsPage.SCREEN)

    settings.tap_dns_settings()
    dns.wait_for_screen(DnsSettingsPage.SCREEN)

    # Step 6: Verify the address persisted.
    value = dns.get_text(DnsSettingsPage.PLAIN_ADDRESS)
    assert "9.9.9.9" in value, (
        f"DNS address should persist as '9.9.9.9', got '{value}'"
    )
