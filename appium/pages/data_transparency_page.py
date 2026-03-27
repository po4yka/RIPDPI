"""Data transparency screen page object."""

from __future__ import annotations

from .base_page import BasePage


class DataTransparencyPage(BasePage):
    SCREEN = "data_transparency-screen"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)
