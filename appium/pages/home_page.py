"""Home screen page object."""

from __future__ import annotations

from .base_page import BasePage


class HomePage(BasePage):
    SCREEN = "home-screen"
    CONNECTION_BUTTON = "home-connection-button"
    APPROACH_CARD = "home-approach-card"
    PERMISSION_ISSUE_BANNER = "home-permission-issue-banner"
    ERROR_BANNER = "home-error-banner"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_connect(self) -> None:
        self.tap(self.CONNECTION_BUTTON)

    def is_connection_button_visible(self) -> bool:
        return self.is_visible(self.CONNECTION_BUTTON)
