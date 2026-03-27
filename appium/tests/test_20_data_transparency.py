"""Test 20: Data transparency screen loads."""

import pytest

from pages.data_transparency_page import DataTransparencyPage


@pytest.mark.automation(
    start_route="data_transparency",
    data_preset="clean_home",
    service_preset="idle",
)
def test_data_transparency_screen(driver):
    page = DataTransparencyPage(driver)
    assert page.is_loaded(), "Data transparency screen should be visible"
