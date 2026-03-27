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
