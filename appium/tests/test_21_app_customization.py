"""Test 21: App customization -- shape info sheet and themed icon."""

import pytest

from pages.app_customization_page import AppCustomizationPage


@pytest.mark.automation(
    start_route="app_customization",
    data_preset="clean_home",
    service_preset="idle",
)
def test_app_customization(driver):
    page = AppCustomizationPage(driver)
    assert page.is_loaded(), "App customization screen should be visible"
    assert page.is_themed_icon_visible(), "Themed icon toggle should be visible"

    page.tap_shape_info()
    assert page.is_shape_info_sheet_visible(), (
        "Shape info sheet should appear after tapping shape info"
    )
