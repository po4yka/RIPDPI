"""Base page object with shared interaction helpers."""

from __future__ import annotations

from typing import TYPE_CHECKING

from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

if TYPE_CHECKING:
    from appium.webdriver import WebElement
    from appium.webdriver.webdriver import WebDriver

from lib.launch_contract import APP_PACKAGE


class BasePage:
    def __init__(self, driver: WebDriver) -> None:
        self.driver = driver

    # -- element lookup -------------------------------------------------------

    def _resource_id(self, tag: str) -> str:
        if ":" in tag:
            return tag
        return f"{APP_PACKAGE}:id/{tag}"

    def find(self, tag: str) -> WebElement:
        return self.driver.find_element(AppiumBy.ID, self._resource_id(tag))

    def wait_for(self, tag: str, timeout: int = 10) -> WebElement:
        locator = (AppiumBy.ID, self._resource_id(tag))
        return WebDriverWait(self.driver, timeout).until(
            EC.presence_of_element_located(locator),
            message=f"Timed out waiting for '{tag}'",
        )

    def is_visible(self, tag: str, timeout: int = 3) -> bool:
        try:
            self.wait_for(tag, timeout=timeout)
            return True
        except (TimeoutException, NoSuchElementException):
            return False

    # -- interactions ---------------------------------------------------------

    def tap(self, tag: str, timeout: int = 10) -> None:
        self.wait_for(tag, timeout=timeout).click()

    def clear_and_type(self, tag: str, text: str, timeout: int = 10) -> None:
        el = self.wait_for(tag, timeout=timeout)
        el.clear()
        el.send_keys(text)

    def scroll_to(self, tag: str, max_swipes: int = 5) -> WebElement:
        """Swipe down until the element is visible, then return it."""
        for _ in range(max_swipes):
            if self.is_visible(tag, timeout=1):
                return self.find(tag)
            size = self.driver.get_window_size()
            start_y = int(size["height"] * 0.8)
            end_y = int(size["height"] * 0.3)
            center_x = int(size["width"] * 0.5)
            self.driver.swipe(center_x, start_y, center_x, end_y, duration=300)
        return self.wait_for(tag, timeout=5)
