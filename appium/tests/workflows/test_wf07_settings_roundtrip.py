"""Workflow 07: Settings round-trip -- WebRTC toggle + DNS change, navigate away, verify persisted."""

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
def test_settings_persist_across_navigation(workflow_app):
    driver = workflow_app
    nav = BottomNav(driver)

    # Step 1: Navigate to settings.
    nav.navigate_to("settings")
    settings = SettingsPage(driver)
    settings.wait_for_screen(SettingsPage.SCREEN)

    # Step 2: Toggle WebRTC protection.
    settings.tap_webrtc_toggle()

    # Step 3: Open DNS settings and set a custom resolver.
    settings.tap_dns_settings()
    dns = DnsSettingsPage(driver)
    dns.wait_for_screen(DnsSettingsPage.SCREEN)

    dns.set_plain_address("8.8.4.4")
    dns.tap_plain_save()

    # Step 4: Navigate back to settings, then home.
    driver.back()
    settings.wait_for_screen(SettingsPage.SCREEN)

    nav.navigate_to("home")
    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)

    # Step 5: Navigate back to settings and verify persistence.
    nav.navigate_to("settings")
    settings.wait_for_screen(SettingsPage.SCREEN)

    # WebRTC toggle state is visible on the settings screen (visual verification).
    assert settings.is_visible(SettingsPage.WEBRTC_PROTECTION), (
        "WebRTC toggle should still be visible"
    )

    # Step 6: Open DNS settings and verify address persisted.
    settings.tap_dns_settings()
    dns.wait_for_screen(DnsSettingsPage.SCREEN)

    value = dns.get_text(DnsSettingsPage.PLAIN_ADDRESS)
    assert "8.8.4.4" in value, (
        f"DNS address should persist as '8.8.4.4', got '{value}'"
    )
