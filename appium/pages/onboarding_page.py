"""Onboarding screen page object."""

from __future__ import annotations

from .base_page import BasePage


class OnboardingPage(BasePage):
    SCREEN = "onboarding-screen"
    SKIP = "onboarding-skip"
    CONTINUE = "onboarding-continue"
    FINISH_ANYWAY = "onboarding-finish-anyway"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_skip(self) -> None:
        if self.is_visible(self.SKIP, timeout=2):
            self.tap(self.SKIP)
        else:
            self.tap_percent(0.88, 0.08)

    def tap_continue(self) -> None:
        self.tap(self.CONTINUE)

    def swipe_to_next_page(self) -> None:
        self.swipe_horizontal("left")

    def complete_all_pages(self, max_steps: int = 10) -> None:
        for _ in range(max_steps):
            if self.is_visible("home-screen", timeout=1):
                return
            if self.is_visible(self.FINISH_ANYWAY, timeout=1):
                self.tap(self.FINISH_ANYWAY)
                return
            if self.is_visible(self.CONTINUE, timeout=1):
                self.tap_continue()
            else:
                self.swipe_to_next_page()
