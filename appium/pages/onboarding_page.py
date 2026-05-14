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
        if self.is_visible(self.SKIP, timeout=2):
            self.tap(self.SKIP)
        else:
            self.tap_text("Skip")

    def tap_continue(self) -> None:
        self.tap(self.CONTINUE)

    def swipe_to_next_page(self) -> None:
        self.swipe_horizontal("left")

    def complete_all_pages(self, max_steps: int = 10) -> None:
        for _ in range(max_steps):
            if self.is_visible("home-screen", timeout=1):
                return
            self.tap_continue()
