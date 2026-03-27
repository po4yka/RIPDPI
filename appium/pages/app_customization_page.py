"""App customization screen page object."""

from __future__ import annotations

from .base_page import BasePage


class AppCustomizationPage(BasePage):
    SCREEN = "app_customization-screen"
    SHAPE_INFO = "customization-shape-info"
    SHAPE_INFO_SHEET = "customization-shape-info-sheet"
    THEMED_ICON = "customization-themed-icon"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def tap_shape_info(self) -> None:
        self.tap(self.SHAPE_INFO)

    def is_shape_info_sheet_visible(self) -> bool:
        return self.is_visible(self.SHAPE_INFO_SHEET)

    def is_themed_icon_visible(self) -> bool:
        return self.is_visible(self.THEMED_ICON)
