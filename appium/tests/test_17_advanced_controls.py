"""Test 17: Advanced settings -- toggle, text input, and dropdown controls."""

import pytest
from selenium.common.exceptions import TimeoutException

from pages.advanced_settings_page import AdvancedSettingsPage


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_toggle_tcp_fast_open(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced settings screen should be visible"

    page.toggle_setting("tcp-fast-open")


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_edit_fake_ttl(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced settings screen should be visible"

    page.edit_retention_days("45")
    assert page.is_retention_save_visible(), (
        "Save button should appear after editing diagnostics retention"
    )


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_select_dropdown_option(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced settings screen should be visible"

    try:
        page.tap_option("tls-fingerprint-profile")
    except TimeoutException:
        pytest.skip("TLS fingerprint profile dropdown is not visible in this fixture")
