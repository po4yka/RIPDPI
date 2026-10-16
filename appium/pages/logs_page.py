"""Logs screen page object."""

from __future__ import annotations

from .base_page import BasePage


class LogsPage(BasePage):
    SCREEN = "logs-screen"
    SAVE = "logs-save"
    CLEAR = "logs-clear"
    AUTO_SCROLL = "logs-auto-scroll"
    STREAM = "logs-stream"
    ACTIONS = "logs-actions"
    FILTERS = "logs-filters"
    SHEET_DONE = "logs-sheet-done"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def open_actions(self) -> None:
        self.tap(self.ACTIONS)

    def open_filters(self) -> None:
        self.tap(self.FILTERS)

    def close_sheet(self) -> None:
        self.scroll_incrementally_to(self.SHEET_DONE).click()

    def tap_save(self) -> None:
        self.tap(self.SAVE)

    def tap_clear(self) -> None:
        self.tap(self.CLEAR)

    def toggle_auto_scroll(self) -> None:
        self.scroll_incrementally_to(self.AUTO_SCROLL).click()

    def select_subsystem_filter(self, subsystem: str) -> None:
        self.scroll_incrementally_to(f"logs-subsystem-{subsystem}").click()

    def select_severity_filter(self, severity: str) -> None:
        tag = f"logs-severity-{severity}"
        self.scroll_down_to(tag, max_swipes=14)
        self.tap(tag)
