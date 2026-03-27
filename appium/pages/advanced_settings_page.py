"""Advanced settings screen page object."""

from __future__ import annotations

from .base_page import BasePage


class AdvancedSettingsPage(BasePage):
    SCREEN = "advanced_settings-screen"
    RETENTION_INPUT = "advanced-input-diagnostics-history-retention-days"
    RETENTION_SAVE = "advanced-save-diagnostics-history-retention-days"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def edit_retention_days(self, value: str) -> None:
        el = self.scroll_to(self.RETENTION_INPUT)
        el.clear()
        el.send_keys(value)

    def is_retention_save_visible(self) -> bool:
        return self.is_visible(self.RETENTION_SAVE)
