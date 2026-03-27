"""Onboarding screen page object."""

from __future__ import annotations

from .base_page import BasePage


class OnboardingPage(BasePage):
    SCREEN = "onboarding-screen"
    SKIP = "onboarding-skip"
    CONTINUE = "onboarding-continue"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_skip(self) -> None:
        self.tap(self.SKIP)

    def tap_continue(self) -> None:
        self.tap(self.CONTINUE)

    def swipe_to_next_page(self) -> None:
        self.swipe_horizontal("left")
