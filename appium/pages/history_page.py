"""History screen page object."""

from __future__ import annotations

from .base_page import BasePage

SECTIONS = ("connections", "diagnostics", "events")


class HistoryPage(BasePage):
    SCREEN = "history-screen"
    CONNECTIONS_SEARCH = "history-connections-search"
    DIAGNOSTICS_SEARCH = "history-diagnostics-search"
    EVENTS_SEARCH = "history-events-search"
    FILTER_CLEAR_ALL = "history-filter-clear-all"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_section(self, section: str) -> None:
        self.tap(f"history-section-{section}")

    def is_section_visible(self, section: str) -> bool:
        return self.is_visible(f"history-section-{section}")

    def type_search(self, section: str, query: str) -> None:
        tag = f"history-{section}-search"
        self.clear_and_type(tag, query)

    def tap_clear_all_filters(self) -> None:
        self.tap(self.FILTER_CLEAR_ALL)
