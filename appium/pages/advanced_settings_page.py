"""Advanced settings screen page object."""

from __future__ import annotations

from .base_page import BasePage


class AdvancedSettingsPage(BasePage):
    SCREEN = "advanced_settings-screen"
    RETENTION_INPUT = "advanced-input-diagnostics-history-retention-days"
    RETENTION_SAVE = "advanced-save-diagnostics-history-retention-days"
    CLEAR_REMEMBERED = "advanced-clear-remembered-networks"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def edit_retention_days(self, value: str) -> None:
        el = self.scroll_to(self.RETENTION_INPUT)
        el.clear()
        el.send_keys(value)

    def is_retention_save_visible(self) -> bool:
        return self.is_visible(self.RETENTION_SAVE)

    def toggle_setting(self, setting_name: str) -> None:
        tag = f"advanced-toggle-{setting_name}"
        self.scroll_to(tag)
        self.tap(tag)

    def edit_input(self, setting_name: str, value: str) -> None:
        tag = f"advanced-input-{setting_name}"
        el = self.scroll_to(tag)
        el.clear()
        el.send_keys(value)

    def is_input_save_visible(self, setting_name: str) -> bool:
        return self.is_visible(f"advanced-save-{setting_name}")

    def tap_option(self, setting_name: str) -> None:
        tag = f"advanced-option-{setting_name}"
        self.scroll_to(tag)
        self.tap(tag)
