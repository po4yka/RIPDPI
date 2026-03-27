"""About screen page object."""

from __future__ import annotations

from .base_page import BasePage


class AboutPage(BasePage):
    SCREEN = "about-screen"
    SOURCE_CODE = "about-source-code"
    README = "about-readme"

    def is_loaded(self) -> bool:
        return self.is_visible(self.SCREEN)

    def is_source_code_visible(self) -> bool:
        return self.is_visible(self.SOURCE_CODE)

    def is_readme_visible(self) -> bool:
        return self.is_visible(self.README)
