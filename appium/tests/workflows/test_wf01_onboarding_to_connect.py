"""Workflow 01: Onboarding -> Home -> Connect."""

import pytest

from pages.home_page import HomePage
from pages.onboarding_page import OnboardingPage


@pytest.mark.workflow
@pytest.mark.automation(
    start_route="onboarding",
    data_preset="clean_home",
    service_preset="idle",
)
def test_onboarding_to_connect(workflow_app):
    driver = workflow_app

    # Step 1: Complete onboarding through the current informational/setup pages.
    onboarding = OnboardingPage(driver)
    assert onboarding.is_loaded(), "Onboarding screen should be visible"

    onboarding.complete_all_pages()

    # Step 2: Verify home screen appeared.
    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)
    assert home.is_loaded(), "Home screen should appear after onboarding"

    # Step 3: Tap the configured primary action.
    assert home.is_any_mode_card_visible(), "A Home mode card should be visible"
    home.tap_any_primary_action()

    # Step 4: Verify the Home mode cards remain visible after the action.
    home.wait_until(
        lambda: home.is_any_mode_card_visible(),
        timeout=15,
        message="Home mode cards should remain visible after primary action",
    )
