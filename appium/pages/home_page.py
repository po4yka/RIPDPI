"""Home screen page object."""

from __future__ import annotations

from .base_page import BasePage


class HomePage(BasePage):
    SCREEN = "home-screen"
    CONNECTION_BUTTON = "home-connection-button"
    APPROACH_CARD = "home-approach-card"
    HISTORY_CARD = "home-history-card"
    STATS_GRID = "home-stats-grid"
    PERMISSION_ISSUE_BANNER = "home-permission-issue-banner"
    PERMISSION_REC_BANNER = "home-permission-recommendation-banner"
    ERROR_BANNER = "home-error-banner"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_connect(self) -> None:
        self.tap(self.CONNECTION_BUTTON)

    def is_connection_button_visible(self) -> bool:
        return self.is_visible(self.CONNECTION_BUTTON)

    def is_stats_grid_visible(self) -> bool:
        return self.is_visible(self.STATS_GRID)

    def is_approach_card_visible(self) -> bool:
        return self.is_visible(self.APPROACH_CARD)

    def is_history_card_visible(self) -> bool:
        return self.is_visible(self.HISTORY_CARD)

    def is_permission_banner_visible(self) -> bool:
        return self.is_visible(self.PERMISSION_ISSUE_BANNER)
