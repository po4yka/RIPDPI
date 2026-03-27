"""Test 32: Support bundle export button on the settings screen."""

import pytest

from pages.base_page import BasePage
from pages.settings_page import SettingsPage

SUPPORT_BUNDLE = "settings-support-bundle"


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_support_bundle_visible(driver):
    settings = SettingsPage(driver)
    assert settings.is_loaded(), "Settings screen should be visible"

    base = BasePage(driver)
    base.scroll_to(SUPPORT_BUNDLE)
    assert base.is_visible(SUPPORT_BUNDLE), (
        "Support bundle button should be visible on settings screen"
    )
    base.tap(SUPPORT_BUNDLE)
