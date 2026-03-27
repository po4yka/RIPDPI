"""Bottom navigation bar page object."""

from __future__ import annotations

from .base_page import BasePage

TABS = ("home", "config", "diagnostics", "settings")


class BottomNav(BasePage):
    def navigate_to(self, tab: str) -> None:
        assert tab in TABS, f"Unknown tab '{tab}', expected one of {TABS}"
        self.tap(f"bottom-nav-{tab}")

    def is_tab_visible(self, tab: str) -> bool:
        return self.is_visible(f"bottom-nav-{tab}")
