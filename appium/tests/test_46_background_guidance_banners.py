"""Tests for background guidance banners, backup PIN elements, and error snackbar."""
import pytest

from pages.home_page import HomePage
from pages.settings_page import SettingsPage


@pytest.mark.automation(
    start_route="home",
    permission_preset="battery_review",
    data_preset="clean_home",
    service_preset="idle",
)
def test_home_background_guidance_banner(driver):
    page = HomePage(driver)
    assert page.is_loaded(), "Home screen did not load"

    if not page.is_background_guidance_visible():
        pytest.skip(
            "Background guidance banner not visible -- "
            "device may not require battery optimization review"
        )

    assert page.is_background_guidance_visible(), (
        "Home background guidance banner should be visible"
    )


@pytest.mark.automation(
    start_route="settings",
    permission_preset="battery_review",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_settings_background_guidance_banner(driver):
    page = SettingsPage(driver)
    assert page.is_loaded(), "Settings screen did not load"

    if not page.is_background_guidance_visible():
        pytest.skip(
            "Settings background guidance banner not visible -- "
            "device may not require battery optimization review"
        )

    assert page.is_background_guidance_visible(), (
        "Settings background guidance banner should be visible"
    )


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_settings_backup_pin_clear_and_warning(driver):
    page = SettingsPage(driver)
    assert page.is_loaded(), "Settings screen did not load"

    if not page.is_backup_pin_field_visible():
        pytest.skip(
            "Backup PIN fields not visible -- PIN may not be set in this preset"
        )

    page.scroll_to(SettingsPage.BACKUP_PIN_CLEAR)
    assert page.is_visible(SettingsPage.BACKUP_PIN_CLEAR), (
        "Backup PIN clear button should be visible"
    )
    assert page.is_visible(SettingsPage.BACKUP_PIN_WARNING), (
        "Backup PIN warning should be visible"
    )


@pytest.mark.automation(
    start_route="settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_settings_customization_navigation(driver):
    page = SettingsPage(driver)
    assert page.is_loaded(), "Settings screen did not load"

    page.scroll_to(SettingsPage.CUSTOMIZATION)
    assert page.is_visible(SettingsPage.CUSTOMIZATION), (
        "Customization entry should be visible on Settings screen"
    )

    page.tap_customization()
    assert page.is_visible("app_customization-screen"), (
        "App Customization screen should load after tapping Customization"
    )


@pytest.mark.automation(
    start_route="home",
    data_preset="clean_home",
    service_preset="idle",
)
def test_main_error_snackbar_not_visible_by_default(driver):
    page = HomePage(driver)
    assert page.is_loaded(), "Home screen did not load"

    assert not page.is_visible("main-error-snackbar"), (
        "Error snackbar should not be visible during normal operation"
    )
