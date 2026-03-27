"""Base page object with shared interaction helpers."""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Callable

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

    def swipe_horizontal(self, direction: str = "left") -> None:
        """Swipe left or right across the center of the screen."""
        size = self.driver.get_window_size()
        center_y = int(size["height"] * 0.5)
        if direction == "left":
            start_x = int(size["width"] * 0.8)
            end_x = int(size["width"] * 0.2)
        else:
            start_x = int(size["width"] * 0.2)
            end_x = int(size["width"] * 0.8)
        self.driver.swipe(start_x, center_y, end_x, center_y, duration=300)

    # -- workflow helpers -----------------------------------------------------

    def wait_until(
        self,
        condition_fn: Callable[[], object],
        timeout: int = 10,
        poll_interval: float = 0.5,
        message: str = "",
    ) -> object:
        """Poll *condition_fn* until it returns a truthy value or timeout."""
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = condition_fn()
            if result:
                return result
            time.sleep(poll_interval)
        raise TimeoutError(message or f"Condition not met within {timeout}s")

    def wait_for_screen(self, screen_tag: str, timeout: int = 15) -> WebElement:
        """Wait for a screen to appear (named alias for screen transitions)."""
        return self.wait_for(screen_tag, timeout=timeout)

    def transition_to(
        self, old_screen_tag: str, new_screen_tag: str, timeout: int = 15,
    ) -> WebElement:
        """Wait for old screen to disappear and new screen to appear."""
        old_locator = (AppiumBy.ID, self._resource_id(old_screen_tag))
        WebDriverWait(self.driver, timeout).until(
            EC.invisibility_of_element_located(old_locator),
            message=f"Old screen '{old_screen_tag}' did not disappear",
        )
        return self.wait_for(new_screen_tag, timeout=timeout)

    def get_text(self, tag: str, timeout: int = 10) -> str:
        """Read the text value of an element."""
        el = self.wait_for(tag, timeout=timeout)
        return el.text or el.get_attribute("text") or ""

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
