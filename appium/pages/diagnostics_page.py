"""Diagnostics screen page object."""

from __future__ import annotations

from .base_page import BasePage

SECTIONS = ("overview", "scan", "live", "approaches", "share")


class DiagnosticsPage(BasePage):
    SCREEN = "diagnostics-screen"
    SHARE_ARCHIVE = "diagnostics-share-archive"
    HISTORY_ACTION = "diagnostics-top-history-action"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def swipe_to_next_section(self) -> None:
        size = self.driver.get_window_size()
        start_x = int(size["width"] * 0.8)
        end_x = int(size["width"] * 0.2)
        center_y = int(size["height"] * 0.5)
        self.driver.swipe(start_x, center_y, end_x, center_y, duration=300)

    def is_section_visible(self, section: str) -> bool:
        return self.is_visible(f"diagnostics-section-{section}")
