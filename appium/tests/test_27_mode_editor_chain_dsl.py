"""Test 27: Mode editor chain DSL field interaction."""

import pytest

from pages.mode_editor_page import ModeEditorPage


@pytest.mark.automation(
    start_route="mode_editor",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_chain_dsl_entry(driver):
    editor = ModeEditorPage(driver)
    assert editor.is_loaded(), "Mode editor screen should be visible"

    editor.scroll_to(editor.CHAIN_DSL)
    editor.clear_and_type(editor.CHAIN_DSL, "fake,disorder")

    editor.tap_save()
    assert not editor.is_validation_error_visible(), (
        "No validation error should appear for valid chain DSL"
    )
