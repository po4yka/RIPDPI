"""Test 32: Support bundle export button on the settings screen."""

import time

import pytest

from pages.base_page import BasePage
from pages.settings_page import SettingsPage

SUPPORT_BUNDLE = "settings-support-bundle"
SUPPORT_EXPORT_PACKAGES = (
    "com.android.intentresolver",
    "com.google.android.documentsui",
    "com.android.documentsui",
)


def wait_for_support_export_surface(driver, timeout: int = 90) -> str | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        package_name = driver.current_package
        if package_name in SUPPORT_EXPORT_PACKAGES:
            return package_name
        time.sleep(1)
    return None


def close_support_export_surface(driver) -> None:
    for package_name in SUPPORT_EXPORT_PACKAGES:
        driver.execute_script(
            "mobile: shell",
            {"command": "am", "args": ["force-stop", package_name]},
        )


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
    focus = wait_for_support_export_surface(driver)
    try:
        assert focus is not None, "Support bundle export surface should open after tapping"
    finally:
        close_support_export_surface(driver)
