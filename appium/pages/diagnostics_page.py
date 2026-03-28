"""Diagnostics screen page object."""

from __future__ import annotations

from .base_page import BasePage

SECTIONS = ("overview", "scan", "live", "approaches", "share")


class DiagnosticsPage(BasePage):
    SCREEN = "diagnostics-screen"
    SHARE_ARCHIVE = "diagnostics-share-archive"
    HISTORY_ACTION = "diagnostics-top-history-action"

    # Scan states
    SCAN_STATE_IDLE = "diagnostics-scan-state-idle"
    SCAN_STATE_PROGRESS = "diagnostics-scan-state-progress"
    SCAN_STATE_CONTENT = "diagnostics-scan-state-content"

    # Scan actions
    SCAN_RUN_RAW = "diagnostics-scan-run-raw"
    SCAN_RUN_IN_PATH = "diagnostics-scan-run-in-path"
    SCAN_CANCEL = "diagnostics-scan-cancel"
    SCAN_PROGRESS_CARD = "diagnostics-scan-progress-card"

    # Strategy probe report
    STRATEGY_PROBE_REPORT = "diagnostics-strategy-probe-report"
    STRATEGY_PROBE_SUMMARY = "diagnostics-strategy-probe-summary"
    STRATEGY_CANDIDATE_DETAIL_SHEET = "diagnostics-strategy-candidate-detail-sheet"
    STRATEGY_CANDIDATE_NOTES = "diagnostics-strategy-candidate-notes-section"
    STRATEGY_CANDIDATE_SIGNATURE = "diagnostics-strategy-candidate-signature-section"
    STRATEGY_CANDIDATE_RESULTS = "diagnostics-strategy-candidate-results-section"

    # Resolver recommendation
    RESOLVER_RECOMMENDATION_CARD = "diagnostics-resolver-recommendation-card"
    RESOLVER_KEEP_SESSION = "diagnostics-resolver-keep-session"
    RESOLVER_SAVE_SETTING = "diagnostics-resolver-save-setting"

    # Strategy audit
    STRATEGY_WINNING_PATH = "diagnostics-strategy-winning-path"
    STRATEGY_WINNING_TCP_ACTION = "diagnostics-strategy-winning-tcp-action"
    STRATEGY_WINNING_QUIC_ACTION = "diagnostics-strategy-winning-quic-action"
    STRATEGY_FULL_MATRIX_TOGGLE = "diagnostics-strategy-full-matrix-toggle"
    STRATEGY_AUDIT_ASSESSMENT = "diagnostics-strategy-audit-assessment"
    STRATEGY_AUDIT_LOW_CONFIDENCE = "diagnostics-strategy-audit-low-confidence-banner"
    STRATEGY_AUDIT_MEDIUM_CONFIDENCE = "diagnostics-strategy-audit-medium-confidence-note"

    # Workflow restriction
    WORKFLOW_RESTRICTION_CARD = "diagnostics-workflow-restriction-card"
    WORKFLOW_RESTRICTION_ACTION = "diagnostics-workflow-restriction-action"

    # Overview
    OVERVIEW_HERO = "diagnostics-overview-hero"
    OVERVIEW_HISTORY_ACTION = "diagnostics-overview-history-action"
    OVERVIEW_AUTOMATIC_PROBE_CARD = "diagnostics-overview-automatic-probe-card"

    # Detail sheets
    SESSION_DETAIL_SHEET = "diagnostics-session-detail-sheet"
    EVENT_DETAIL_SHEET = "diagnostics-event-detail-sheet"
    PROBE_DETAIL_SHEET = "diagnostics-probe-detail-sheet"

    # Hidden probe conflict dialog
    HIDDEN_PROBE_CONFLICT_DIALOG = "diagnostics-hidden-probe-conflict-dialog"
    HIDDEN_PROBE_CONFLICT_WAIT = "diagnostics-hidden-probe-conflict-wait"
    HIDDEN_PROBE_CONFLICT_CANCEL_AND_RUN = "diagnostics-hidden-probe-conflict-cancel-and-run"
    HIDDEN_PROBE_CONFLICT_DISMISS = "diagnostics-hidden-probe-conflict-dismiss"

    # Search and filters
    SESSIONS_SEARCH = "diagnostics-sessions-search"
    EVENTS_SEARCH = "diagnostics-events-search"
    EVENTS_AUTO_SCROLL = "diagnostics-events-auto-scroll"
    SESSION_SENSITIVE_TOGGLE = "diagnostics-session-sensitive-toggle"

    # Share actions
    SAVE_ARCHIVE = "diagnostics-save-archive"
    SAVE_LOGS = "diagnostics-save-logs"
    SHARE_SUMMARY = "diagnostics-share-summary"
    SHARE_PREVIEW_CARD = "diagnostics-share-preview-card"
    ARCHIVE_STATE_INDICATOR = "diagnostics-archive-state-indicator"
    STATUS_SNACKBAR = "diagnostics-status-snackbar"

    # State indicators
    SESSIONS_STATE_EMPTY = "diagnostics-sessions-state-empty"
    SESSIONS_STATE_CONTENT = "diagnostics-sessions-state-content"
    EVENTS_STATE_EMPTY = "diagnostics-events-state-empty"
    EVENTS_STATE_CONTENT = "diagnostics-events-state-content"

    # Approach modes
    APPROACH_MODE_PROFILES = "diagnostics-approach-mode-profiles"
    APPROACH_MODE_STRATEGIES = "diagnostics-approach-mode-strategies"
    APPROACH_DETAIL_SHEET = "diagnostics-approach-detail-sheet"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def swipe_to_next_section(self) -> None:
        self.swipe_horizontal("left")

    def is_section_visible(self, section: str) -> bool:
        return self.is_visible(f"diagnostics-section-{section}")

    # -- scan helpers ---------------------------------------------------------

    def swipe_to_scan_section(self) -> None:
        self.swipe_to_next_section()

    def swipe_to_approaches_section(self) -> None:
        for _ in range(3):
            self.swipe_to_next_section()

    def is_scan_idle(self, timeout: int = 3) -> bool:
        return self.is_visible(self.SCAN_STATE_IDLE, timeout=timeout)

    def is_scan_in_progress(self, timeout: int = 3) -> bool:
        return self.is_visible(self.SCAN_STATE_PROGRESS, timeout=timeout)

    def is_scan_has_content(self, timeout: int = 3) -> bool:
        return self.is_visible(self.SCAN_STATE_CONTENT, timeout=timeout)

    def tap_run_raw_scan(self) -> None:
        self.tap(self.SCAN_RUN_RAW)

    def tap_cancel_scan(self) -> None:
        self.tap(self.SCAN_CANCEL)

    def tap_approach_mode_strategies(self) -> None:
        self.tap(self.APPROACH_MODE_STRATEGIES)

    def tap_approach_mode_profiles(self) -> None:
        self.tap(self.APPROACH_MODE_PROFILES)

    # -- detail sheet helpers ----------------------------------------------------

    def tap_session(self, session_id: str) -> None:
        self.tap(f"diagnostics-session-{session_id}")

    def tap_event(self, event_id: str) -> None:
        self.tap(f"diagnostics-event-{event_id}")

    def tap_probe(self, probe_id: str) -> None:
        self.tap(f"diagnostics-probe-{probe_id}")

    def tap_strategy_candidate(self, candidate_id: str) -> None:
        self.tap(f"diagnostics-strategy-candidate-{candidate_id}")

    # -- search/filter helpers ---------------------------------------------------

    def type_sessions_search(self, query: str) -> None:
        self.clear_and_type(self.SESSIONS_SEARCH, query)

    def type_events_search(self, query: str) -> None:
        self.clear_and_type(self.EVENTS_SEARCH, query)

    def tap_session_path_filter(self, path_mode: str) -> None:
        self.tap(f"diagnostics-session-path-{path_mode}")

    def tap_session_status_filter(self, status: str) -> None:
        self.tap(f"diagnostics-session-status-{status}")

    def tap_event_source_filter(self, source: str) -> None:
        self.tap(f"diagnostics-event-source-{source}")

    def tap_event_severity_filter(self, severity: str) -> None:
        self.tap(f"diagnostics-event-severity-{severity}")

    # -- overview helpers --------------------------------------------------------

    def swipe_to_overview_section(self) -> None:
        """Overview is the first section; no swipe needed from start."""
        pass

    def swipe_to_share_section(self) -> None:
        for _ in range(4):
            self.swipe_to_next_section()
