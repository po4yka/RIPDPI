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
    initial_webrtc = settings.is_webrtc_enabled()
    settings.tap_webrtc_toggle()
    settings.wait_until(lambda: settings.is_webrtc_enabled() != initial_webrtc)
    assert settings.is_webrtc_enabled() != initial_webrtc, "WebRTC toggle should change state"
    nav.navigate_to("home")
    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)
    nav.navigate_to("settings")
    settings.wait_for_screen(SettingsPage.SCREEN)

    # Step 3: Open DNS settings and set a custom resolver.
    settings.tap_dns_settings()
    dns = DnsSettingsPage(driver)
    dns.wait_for_screen(DnsSettingsPage.SCREEN)

    dns.select_mode("plain-udp")
    dns.set_plain_address("8.8.4.4")
    assert dns.is_plain_save_enabled(), "Save should be enabled for the changed DNS address"
    dns.tap_plain_save()
    dns.wait_until(lambda: not dns.is_plain_save_enabled())
    assert not dns.is_plain_save_enabled(), "Save should be disabled after the address is stored"

    # Step 4: Navigate back to settings, then home.
    driver.back()
    settings.wait_for_screen(SettingsPage.SCREEN)

    nav.navigate_to("home")
    home.wait_for_screen(HomePage.SCREEN)

    # Step 5: Navigate back to settings and verify persistence.
    nav.navigate_to("settings")
    settings.wait_for_screen(SettingsPage.SCREEN)

    assert settings.is_webrtc_enabled() != initial_webrtc, (
        "WebRTC protection should retain its changed state after navigation"
    )
    nav.navigate_to("home")
    home.wait_for_screen(HomePage.SCREEN)
    nav.navigate_to("settings")
    settings.wait_for_screen(SettingsPage.SCREEN)

    # Step 6: Open DNS settings and verify address persisted.
    settings.tap_dns_settings()
    dns.wait_for_screen(DnsSettingsPage.SCREEN)

    assert dns.is_mode_selected("plain-udp"), "Plain DNS mode should persist after navigation"
    value = dns.get_plain_address()
    assert value == "8.8.4.4", (
        f"DNS address should persist as '8.8.4.4', got '{value}'"
    )
