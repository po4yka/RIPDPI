"""Test 06: Select a config preset on the config screen."""

import pytest

from pages.config_page import ConfigPage


@pytest.mark.automation(
    start_route="config",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_select_preset(driver):
    config = ConfigPage(driver)
    assert config.is_loaded(), "Config screen should be visible"

    # Tap the proxy preset card in the current Config layout.
    config.select_preset("proxy")

    # The preset should still be visible (selection doesn't navigate away).
    assert config.is_preset_visible("proxy"), (
        "Proxy preset card should remain visible after selection"
    )
