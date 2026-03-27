"""Test 28: Biometric prompt screen -- PIN entry stage."""

import pytest

from pages.base_page import BasePage


@pytest.mark.automation(
    start_route="biometric_prompt",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_biometric_prompt_screen(driver):
    page = BasePage(driver)

    assert page.is_visible("biometric_prompt-screen"), (
        "Biometric prompt screen should be visible"
    )

    # Primary action button should be present.
    assert page.is_visible("biometric-prompt-primary-action"), (
        "Primary action button should be visible"
    )


@pytest.mark.automation(
    start_route="biometric_prompt",
    data_preset="settings_ready",
    service_preset="idle",
)
def test_biometric_prompt_secondary_action(driver):
    page = BasePage(driver)

    assert page.is_visible("biometric_prompt-screen"), (
        "Biometric prompt screen should be visible"
    )

    # If secondary action (Use PIN) is available, tap it to switch stages.
    if page.is_visible("biometric-prompt-secondary-action"):
        page.tap("biometric-prompt-secondary-action")
        assert page.is_visible("biometric-prompt-pin-field"), (
            "PIN field should appear after switching to PIN stage"
        )
