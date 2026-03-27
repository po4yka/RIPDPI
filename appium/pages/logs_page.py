"""Logs screen page object."""

from __future__ import annotations

from .base_page import BasePage


class LogsPage(BasePage):
    SCREEN = "logs-screen"
    SAVE = "logs-save"
    CLEAR = "logs-clear"
    AUTO_SCROLL = "logs-auto-scroll"
    STREAM = "logs-stream"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_save(self) -> None:
        self.tap(self.SAVE)

    def tap_clear(self) -> None:
        self.tap(self.CLEAR)

    def toggle_auto_scroll(self) -> None:
        self.tap(self.AUTO_SCROLL)

    def select_subsystem_filter(self, subsystem: str) -> None:
        self.tap(f"logs-subsystem-{subsystem}")

    def select_severity_filter(self, severity: str) -> None:
        self.tap(f"logs-severity-{severity}")
