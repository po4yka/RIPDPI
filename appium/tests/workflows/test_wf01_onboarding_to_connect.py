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

    # Step 1: Complete onboarding by swiping through 3 pages.
    onboarding = OnboardingPage(driver)
    assert onboarding.is_loaded(), "Onboarding screen should be visible"

    onboarding.swipe_to_next_page()
    onboarding.swipe_to_next_page()
    onboarding.tap_continue()

    # Step 2: Verify home screen appeared.
    home = HomePage(driver)
    home.wait_for_screen(HomePage.SCREEN)
    assert home.is_loaded(), "Home screen should appear after onboarding"

    # Step 3: Tap connect.
    assert home.is_connection_button_visible(), "Connection button should be visible"
    home.tap_connect()

    # Step 4: Verify button remains visible after connection attempt.
    home.wait_until(
        lambda: home.is_connection_button_visible(),
        timeout=15,
        message="Connection button should remain visible after connect",
    )
