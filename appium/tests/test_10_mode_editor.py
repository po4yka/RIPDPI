"""Test 10: Mode editor form fill and validation."""

import pytest

from pages.mode_editor_page import ModeEditorPage


@pytest.mark.automation(
    start_route="mode_editor",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_mode_editor_valid_save(driver):
    editor = ModeEditorPage(driver)
    assert editor.is_loaded(), "Mode editor screen should be visible"

    editor.fill_proxy("127.0.0.1", "1080")
    editor.set_max_connections("512")
    editor.set_buffer_size("65536")
    editor.set_default_ttl("128")
    editor.tap_save()

    assert not editor.is_validation_error_visible(), (
        "No validation error should appear for valid input"
    )


@pytest.mark.automation(
    start_route="mode_editor",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_mode_editor_command_line_toggle(driver):
    editor = ModeEditorPage(driver)
    assert editor.is_loaded(), "Mode editor screen should be visible"

    editor.toggle_command_line()
    assert editor.is_visible(editor.CMD_ARGS), (
        "Command line args field should appear after toggling"
    )
