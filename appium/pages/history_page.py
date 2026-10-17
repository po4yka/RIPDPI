"""History screen page object."""

from __future__ import annotations

from selenium.common.exceptions import TimeoutException, WebDriverException

from .base_page import BasePage

SECTIONS = ("connections", "diagnostics", "events")


class HistoryPage(BasePage):
    SCREEN = "history-screen"
    CONNECTIONS_SEARCH = "history-connections-search"
    DIAGNOSTICS_SEARCH = "history-diagnostics-search"
    EVENTS_SEARCH = "history-events-search"
    FILTER_CLEAR_ALL = "history-filter-clear-all"
    EVENTS_AUTO_SCROLL = "history-events-auto-scroll"

    def __init__(self, driver) -> None:
        super().__init__(driver)
        self._section = "connections"

    def _list_tag(self, tag: str) -> str | None:
        tag = tag.split(":id/", 1)[-1]
        if (tag == "history-events-jump-to-latest" or
                tag.startswith("history-section-") or tag.endswith("-detail-sheet")):
            return None
        if tag.startswith(("history-connections-", "history-connection-")):
            section = "connections"
        elif tag.startswith("history-diagnostics-"):
            section = "diagnostics"
        elif tag.startswith(("history-events-", "history-event-")):
            section = "events"
        elif tag in (self.FILTER_CLEAR_ALL, "history-no-results", "history-empty-clear-filters"):
            section = self._section
        else:
            return None
        return f"history-{section}-list"

    @staticmethod
    def _is_filter_control(tag: str) -> bool:
        return any(part in tag for part in (
            "-search", "-filter", "-mode-", "-status-", "-path-", "-source-", "-severity-", "-auto-scroll",
        ))

    def scroll_to(self, tag: str, max_swipes: int = 5):
        container = self._list_tag(tag)
        if container:
            return self.scroll_to_in_container(
                tag, container, max_swipes=max_swipes, fully_visible=self._is_filter_control(tag),
            )
        return super().scroll_to(tag, max_swipes=max_swipes)

    def tap(self, tag: str, timeout: int = 10) -> None:
        container = self._list_tag(tag)
        if container:
            self.tap_in_container(tag, container, fully_visible=self._is_filter_control(tag))
        else:
            super().tap(tag, timeout=timeout)

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_section(self, section: str) -> None:
        if section not in SECTIONS:
            raise ValueError(f"Unknown history section: {section!r}")
        self.tap(f"history-section-{section}")
        self._section = section

    def is_section_visible(self, section: str) -> bool:
        return self.is_visible(f"history-section-{section}")

    def open_filters(self, section: str) -> None:
        """Open a section's saved disclosure once, without toggling it closed."""
        search_tag = f"history-{section}-search"
        disclosure_tag = f"{search_tag}-filter-disclosure"
        self.scroll_to(disclosure_tag)
        if not self.is_selected(disclosure_tag):
            self.tap(disclosure_tag)

    def type_search(self, section: str, query: str) -> None:
        tag = f"history-{section}-search"
        self.scroll_to(tag)
        self.find(tag).clear()
        self.scroll_to(tag)
        self.find(tag).send_keys(query)
        try:
            self.driver.hide_keyboard()
        except WebDriverException:
            pass

    def tap_auto_scroll(self) -> None:
        self.open_filters("events")
        self.tap(self.EVENTS_AUTO_SCROLL)

    def is_auto_scroll_visible(self) -> bool:
        return self.is_visible(self.EVENTS_AUTO_SCROLL)

    def tap_clear_all_filters(self) -> None:
        self.tap(self.FILTER_CLEAR_ALL)

    # -- filter helpers ----------------------------------------------------------

    def tap_first_filter_option(self, section: str, group: str) -> str:
        """Choose an available option explicitly when a legacy fixture varies."""
        prefixes = {
            ("connections", "mode"): "history-connections-mode-",
            ("connections", "status"): "history-connections-status-",
            ("diagnostics", "path"): "history-diagnostics-path-",
            ("diagnostics", "status"): "history-diagnostics-status-",
            ("events", "source"): "history-event-source-",
            ("events", "severity"): "history-event-severity-",
        }
        prefix = prefixes[(section, group)]
        self.open_filters(section)
        container = f"history-{section}-list"
        def choose_visible_option() -> str | None:
            element = self._find_by_test_tag_prefix(prefix)
            if not element:
                return None
            tag = element.get_attribute("resource-id").split(":id/", 1)[-1]
            if not tag.startswith(prefix):
                raise ValueError(f"Unexpected filter identity: {tag!r}")
            self.tap(tag)
            return tag

        for direction in ("down", "up"):
            for _ in range(5):
                chosen = choose_visible_option()
                if chosen:
                    return chosen
                self._scroll_container_once(container, direction)
        chosen = choose_visible_option()
        if chosen:
            return chosen
        raise TimeoutException(f"No {group!r} filter option in history section {section!r}")

    def _tap_filter(self, section: str, tag: str) -> None:
        self.open_filters(section)
        self.tap(tag)

    def tap_connections_mode_filter(self, mode: str) -> None:
        self._tap_filter("connections", f"history-connections-mode-{mode}")

    def tap_connections_status_filter(self, status: str) -> None:
        self._tap_filter("connections", f"history-connections-status-{status}")

    def tap_diagnostics_path_filter(self, path_mode: str) -> None:
        self._tap_filter("diagnostics", f"history-diagnostics-path-{path_mode}")

    def tap_diagnostics_status_filter(self, status: str) -> None:
        self._tap_filter("diagnostics", f"history-diagnostics-status-{status}")

    def tap_event_source_filter(self, source: str) -> None:
        self._tap_filter("events", f"history-event-source-{source}")

    def tap_event_severity_filter(self, severity: str) -> None:
        self._tap_filter("events", f"history-event-severity-{severity}")

    # -- detail sheet helpers ----------------------------------------------------

    def tap_connection(self, session_id: str) -> None:
        self.tap(f"history-connection-{session_id}")

    def tap_first_connection(self) -> None:
        self.tap_first_with_prefix("history-connection-")

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

    def is_connection_detail_visible(self) -> bool:
        return self.is_visible(self.CONNECTION_DETAIL_SHEET, timeout=5) or (
            self.is_text_visible("Network", timeout=2)
            and self.is_text_visible("Service", timeout=2)
        )
