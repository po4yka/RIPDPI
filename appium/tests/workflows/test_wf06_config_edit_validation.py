"""Workflow 06: Config -> edit mode -> invalid port -> validation error -> fix -> save."""

import pytest

from pages.bottom_nav import BottomNav
from pages.config_page import ConfigPage
from pages.home_page import HomePage
from pages.mode_editor_page import ModeEditorPage


@pytest.mark.workflow
@pytest.mark.automation(
    start_route="home",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_config_edit_validation_roundtrip(workflow_app):
    driver = workflow_app
    nav = BottomNav(driver)

    # Step 1: Navigate to config.
    nav.navigate_to("config")
    config = ConfigPage(driver)
    config.wait_for_screen(ConfigPage.SCREEN)

    # Step 2: Open mode editor.
    config.tap_edit_current()
    editor = ModeEditorPage(driver)
    editor.wait_for_screen(ModeEditorPage.SCREEN)

    # Step 3: Enter invalid proxy port and save.
    editor.fill_proxy("127.0.0.1", "999999")
    editor.tap_save()

    # Step 4: Verify validation error appears.
    editor.wait_until(
        lambda: editor.is_validation_error_visible(),
        timeout=5,
        message="Validation snackbar should appear for invalid port",
    )

    # Step 5: Fix the port to a valid value and save.
    editor.fill_proxy("127.0.0.1", "8080")
    editor.tap_save()

    # Step 6: Verify we return to config screen (no validation error).
    config.wait_for_screen(ConfigPage.SCREEN, timeout=10)
    assert config.is_loaded(), "Config screen should appear after successful save"
