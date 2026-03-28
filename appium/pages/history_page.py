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
    EVENTS_AUTO_SCROLL = "history-events-auto-scroll"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_section(self, section: str) -> None:
        self.tap(f"history-section-{section}")

    def is_section_visible(self, section: str) -> bool:
        return self.is_visible(f"history-section-{section}")

    def type_search(self, section: str, query: str) -> None:
        tag = f"history-{section}-search"
        self.clear_and_type(tag, query)

    def tap_auto_scroll(self) -> None:
        self.tap(self.EVENTS_AUTO_SCROLL)

    def is_auto_scroll_visible(self) -> bool:
        return self.is_visible(self.EVENTS_AUTO_SCROLL)

    def tap_clear_all_filters(self) -> None:
        self.tap(self.FILTER_CLEAR_ALL)

    # -- filter helpers ----------------------------------------------------------

    def tap_connections_mode_filter(self, mode: str) -> None:
        self.tap(f"history-connections-mode-{mode}")

    def tap_connections_status_filter(self, status: str) -> None:
        self.tap(f"history-connections-status-{status}")

    def tap_diagnostics_path_filter(self, path_mode: str) -> None:
        self.tap(f"history-diagnostics-path-{path_mode}")

    def tap_diagnostics_status_filter(self, status: str) -> None:
        self.tap(f"history-diagnostics-status-{status}")

    def tap_event_source_filter(self, source: str) -> None:
        self.tap(f"history-event-source-{source}")

    def tap_event_severity_filter(self, severity: str) -> None:
        self.tap(f"history-event-severity-{severity}")

    # -- detail sheet helpers ----------------------------------------------------

    def tap_connection(self, session_id: str) -> None:
        self.tap(f"history-connection-{session_id}")

    def tap_diagnostics_session(self, session_id: str) -> None:
        self.tap(f"history-diagnostics-{session_id}")

    def tap_event(self, event_id: str) -> None:
        self.tap(f"history-event-{event_id}")

    # -- state helpers -----------------------------------------------------------

    CONNECTIONS_STATE_EMPTY = "history-connections-state-empty"
    CONNECTIONS_STATE_CONTENT = "history-connections-state-content"
    DIAGNOSTICS_STATE_EMPTY = "history-diagnostics-state-empty"
    DIAGNOSTICS_STATE_CONTENT = "history-diagnostics-state-content"
    EVENTS_STATE_EMPTY = "history-events-state-empty"
    EVENTS_STATE_CONTENT = "history-events-state-content"
    CONNECTION_DETAIL_SHEET = "history-connection-detail-sheet"
    DIAGNOSTICS_DETAIL_SHEET = "history-diagnostics-detail-sheet"
    EVENT_DETAIL_SHEET = "history-event-detail-sheet"

    def is_connections_content_visible(self) -> bool:
        return self.is_visible(self.CONNECTIONS_STATE_CONTENT)

    def is_diagnostics_content_visible(self) -> bool:
        return self.is_visible(self.DIAGNOSTICS_STATE_CONTENT)

    def is_events_content_visible(self) -> bool:
        return self.is_visible(self.EVENTS_STATE_CONTENT)
