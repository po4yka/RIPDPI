"""Home screen page object."""

from __future__ import annotations

from .base_page import BasePage


class HomePage(BasePage):
    SCREEN = "home-screen"
    CONNECTION_BUTTON = "home-mode-primary-remote-vpn"
    LOCAL_BYPASS_BUTTON = "home-mode-primary-local-dpi-bypass"
    DIAGNOSTIC_BUTTON = "home-mode-primary-diagnostic"
    APPROACH_CARD = "home-mode-card-diagnostic"
    HISTORY_CARD = "bottom-nav-diagnostics"
    STATS_GRID = "home-mode-card-remote-vpn"
    PERMISSION_ISSUE_BANNER = "home-permission-issue-banner"
    PERMISSION_REC_BANNER = "home-permission-recommendation-banner"
    ERROR_BANNER = "home-error-banner"
    WARNING_BANNER_DISMISS = "warning-banner-dismiss"
    BACKGROUND_GUIDANCE_BANNER = "home-background-guidance-banner"

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

    def dismiss_warning_banner(self) -> None:
        self.tap(self.WARNING_BANNER_DISMISS)

    def is_background_guidance_visible(self) -> bool:
        return self.is_visible(self.BACKGROUND_GUIDANCE_BANNER)
