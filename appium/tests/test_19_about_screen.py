"""Test 19: About screen -- links visible."""

import pytest

from pages.about_page import AboutPage


@pytest.mark.automation(
    start_route="about",
    data_preset="clean_home",
    service_preset="idle",
)
def test_about_screen(driver):
    about = AboutPage(driver)
    assert about.is_loaded(), "About screen should be visible"
    assert about.is_source_code_visible(), "Source code link should be visible"
    assert about.is_readme_visible(), "Readme link should be visible"
