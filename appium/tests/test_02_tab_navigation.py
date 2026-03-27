"""Test 02: Bottom nav tab switching across all 4 tabs."""

import pytest

from pages.bottom_nav import BottomNav, TABS
from pages.config_page import ConfigPage
from pages.diagnostics_page import DiagnosticsPage
from pages.home_page import HomePage
from pages.settings_page import SettingsPage


SCREEN_PAGES = {
    "home": HomePage,
    "config": ConfigPage,
    "diagnostics": DiagnosticsPage,
    "settings": SettingsPage,
}


@pytest.mark.automation(
    start_route="home",
    data_preset="clean_home",
    service_preset="idle",
)
def test_tab_navigation(driver):
    nav = BottomNav(driver)

    for tab in TABS:
        nav.navigate_to(tab)
        page = SCREEN_PAGES[tab](driver)
        assert page.is_loaded(), f"{tab} screen should be visible after tapping its tab"
