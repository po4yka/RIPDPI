"""App customization screen page object."""

from __future__ import annotations

from .base_page import BasePage


class AppCustomizationPage(BasePage):
    SCREEN = "app_customization-screen"
    SHAPE_INFO = "customization-shape-info"
    SHAPE_INFO_SHEET = "customization-shape-info-sheet"
    SHAPE_INFO_SHEET_CONFIRM = "customization-shape-info-sheet-confirm"
    THEMED_ICON = "customization-themed-icon"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_shape_info(self) -> None:
        self.tap(self.SHAPE_INFO)

    def is_shape_info_sheet_visible(self) -> bool:
        return self.is_visible(self.SHAPE_INFO_SHEET, timeout=8) or self.is_visible(
            self.SHAPE_INFO_SHEET_CONFIRM,
            timeout=3,
        ) or self.is_text_visible("Adaptive shape", timeout=3)

    def is_themed_icon_visible(self) -> bool:
        return self.is_visible(self.THEMED_ICON)
