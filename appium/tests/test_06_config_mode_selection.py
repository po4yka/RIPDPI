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

    # Tap the "medium" preset chip.
    config.select_preset("medium")

    # The preset should still be visible (selection doesn't navigate away).
    assert config.is_preset_visible("medium"), (
        "Medium preset chip should remain visible after selection"
    )
