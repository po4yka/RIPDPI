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
        self.swipe_horizontal("left")

    def is_section_visible(self, section: str) -> bool:
        return self.is_visible(f"diagnostics-section-{section}")
