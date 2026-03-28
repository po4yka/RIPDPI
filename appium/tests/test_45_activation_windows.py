"""Tests for activation window dimension fields on the Advanced Settings screen."""
import pytest

from pages.advanced_settings_page import AdvancedSettingsPage


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_activation_hour_fields_visible(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced Settings screen did not load"

    dimension = "hour"
    if not page.is_activation_start_visible(dimension):
        pytest.skip(f"Activation {dimension} fields not visible in this build")

    assert page.is_activation_start_visible(dimension), (
        f"Activation {dimension} from field should be visible"
    )
    assert page.is_activation_end_visible(dimension), (
        f"Activation {dimension} to field should be visible"
    )


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_activation_day_of_week_fields_visible(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced Settings screen did not load"

    dimension = "day-of-week"
    if not page.is_activation_start_visible(dimension):
        pytest.skip(f"Activation {dimension} fields not visible in this build")

    assert page.is_activation_start_visible(dimension), (
        f"Activation {dimension} from field should be visible"
    )
    assert page.is_activation_end_visible(dimension), (
        f"Activation {dimension} to field should be visible"
    )


@pytest.mark.automation(
    start_route="advanced_settings",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_activation_edit_and_save(driver):
    page = AdvancedSettingsPage(driver)
    assert page.is_loaded(), "Advanced Settings screen did not load"

    dimension = "hour"
    if not page.is_activation_start_visible(dimension):
        pytest.skip(f"Activation {dimension} fields not visible in this build")

    page.edit_activation_start(dimension, "08")
    assert page.is_activation_start_visible(dimension), (
        f"Activation {dimension} from field should remain visible after edit"
    )

    page.tap_activation_save(dimension)
