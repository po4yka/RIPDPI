"""Test 16: Onboarding flow -- skip and swipe-through paths."""

import pytest

from pages.home_page import HomePage
from pages.onboarding_page import OnboardingPage


@pytest.mark.automation(
    start_route="onboarding",
    data_preset="clean_home",
    service_preset="idle",
)
def test_onboarding_skip(driver):
    onboarding = OnboardingPage(driver)
    assert onboarding.is_loaded(), "Onboarding screen should be visible"

    onboarding.tap_skip()

    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should appear after skipping onboarding"


@pytest.mark.automation(
    start_route="onboarding",
    data_preset="clean_home",
    service_preset="idle",
)
def test_onboarding_complete(driver):
    onboarding = OnboardingPage(driver)
    assert onboarding.is_loaded(), "Onboarding screen should be visible"

    # Swipe through 3 pages.
    onboarding.swipe_to_next_page()
    onboarding.swipe_to_next_page()

    # Tap continue on the last page.
    onboarding.tap_continue()

    home = HomePage(driver)
    assert home.is_loaded(), "Home screen should appear after completing onboarding"
