"""Base page object with shared interaction helpers."""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Callable

from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException, WebDriverException
from selenium.webdriver.support.ui import WebDriverWait

if TYPE_CHECKING:
    from appium.webdriver import WebElement
    from appium.webdriver.webdriver import WebDriver

from lib.launch_contract import APP_PACKAGE


class BasePage:
    def __init__(self, driver: WebDriver) -> None:
        self.driver = driver

    # -- element lookup -------------------------------------------------------

    def _resource_ids(self, tag: str) -> list[str]:
        if ":" in tag:
            return [tag]
        return [tag, f"{APP_PACKAGE}:id/{tag}"]

    def _find_by_test_tag(self, tag: str) -> WebElement | bool:
        for resource_id in self._resource_ids(tag):
            try:
                return self.driver.find_element(AppiumBy.ID, resource_id)
            except (NoSuchElementException, WebDriverException):
                continue
        for resource_id in self._resource_ids(tag):
            try:
                return self.driver.find_element(AppiumBy.XPATH, f'//*[@resource-id="{resource_id}"]')
            except NoSuchElementException:
                continue
        return False

    def _scroll_into_view_by_uiautomator(self, tag: str) -> WebElement | bool:
        for resource_id in self._resource_ids(tag):
            escaped_id = resource_id.replace('"', '\\"')
            selector = (
                'new UiScrollable(new UiSelector().scrollable(true))'
                f'.scrollIntoView(new UiSelector().resourceId("{escaped_id}"))'
            )
            try:
                return self.driver.find_element(AppiumBy.ANDROID_UIAUTOMATOR, selector)
            except (NoSuchElementException, WebDriverException):
                continue
        return False

    def _find_by_test_tag_prefix(self, prefix: str) -> WebElement | bool:
        escaped_prefix = prefix.replace('"', '\\"')
        try:
            return self.driver.find_element(
                AppiumBy.XPATH,
                f'//*[contains(@resource-id, "{escaped_prefix}")]',
            )
        except (NoSuchElementException, WebDriverException):
            return False

    def find(self, tag: str) -> WebElement:
        element = self._find_by_test_tag(tag)
        if element:
            return element
        raise NoSuchElementException(f"Could not find element with tag {tag!r}")

    def wait_for(self, tag: str, timeout: int = 10) -> WebElement:
        return WebDriverWait(self.driver, timeout).until(
            lambda _: self._find_by_test_tag(tag),
            message=f"Timed out waiting for '{tag}'",
        )

    def is_visible(self, tag: str, timeout: int = 3) -> bool:
        try:
            self.wait_for(tag, timeout=timeout)
            return True
        except (TimeoutException, NoSuchElementException):
            return False

    def is_prefix_visible(self, prefix: str, timeout: int = 3) -> bool:
        try:
            self.wait_for_prefix(prefix, timeout=timeout)
            return True
        except (TimeoutException, NoSuchElementException):
            return False

    # -- interactions ---------------------------------------------------------

    def tap(self, tag: str, timeout: int = 10) -> None:
        self.wait_for(tag, timeout=timeout).click()

    def tap_first_with_prefix(self, prefix: str, timeout: int = 10) -> None:
        self.wait_for_prefix(prefix, timeout=timeout).click()

    def tap_text(self, text: str, timeout: int = 10) -> None:
        WebDriverWait(self.driver, timeout).until(
            lambda _: self.driver.find_element(AppiumBy.XPATH, f'//*[@text="{text}"]'),
            message=f"Timed out waiting for text {text!r}",
        ).click()

    def is_text_visible(self, text: str, timeout: int = 3) -> bool:
        try:
            WebDriverWait(self.driver, timeout).until(
                lambda _: self.driver.find_element(AppiumBy.XPATH, f'//*[@text="{text}"]'),
                message=f"Timed out waiting for text {text!r}",
            )
            return True
        except (TimeoutException, NoSuchElementException):
            return False

    def tap_percent(self, x_percent: float, y_percent: float) -> None:
        size = self.driver.get_window_size()
        x = int(size["width"] * x_percent)
        y = int(size["height"] * y_percent)
        try:
            self.driver.tap([(x, y)], 120)
        except (AttributeError, WebDriverException):
            pass
        self.driver.execute_script(
            "mobile: clickGesture",
            {"x": x, "y": y},
        )

    def clear_and_type(self, tag: str, text: str, timeout: int = 10) -> None:
        el = self.wait_for(tag, timeout=timeout)
        el.clear()
        el.send_keys(text)
        try:
            self.driver.hide_keyboard()
        except WebDriverException:
            pass

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

    def wait_for_prefix(self, prefix: str, timeout: int = 10) -> WebElement:
        return WebDriverWait(self.driver, timeout).until(
            lambda _: self._find_by_test_tag_prefix(prefix),
            message=f"Timed out waiting for prefix '{prefix}'",
        )

    def transition_to(
        self, old_screen_tag: str, new_screen_tag: str, timeout: int = 15,
    ) -> WebElement:
        """Wait for old screen to disappear and new screen to appear."""
        WebDriverWait(self.driver, timeout).until(
            lambda _: not self._find_by_test_tag(old_screen_tag),
            message=f"Old screen '{old_screen_tag}' did not disappear",
        )
        return self.wait_for(new_screen_tag, timeout=timeout)

    def get_text(self, tag: str, timeout: int = 10) -> str:
        """Read the text value of an element."""
        el = self.wait_for(tag, timeout=timeout)
        return el.text or el.get_attribute("text") or ""

    def scroll_to(self, tag: str, max_swipes: int = 5) -> WebElement:
        """Swipe down until the element is visible, then return it."""
        element = self._scroll_into_view_by_uiautomator(tag)
        if element:
            return element
        for _ in range(max_swipes):
            if self.is_visible(tag, timeout=1):
                return self.find(tag)
            size = self.driver.get_window_size()
            start_y = int(size["height"] * 0.8)
            end_y = int(size["height"] * 0.3)
            center_x = int(size["width"] * 0.5)
            self.driver.swipe(center_x, start_y, center_x, end_y, duration=300)
            time.sleep(0.3)
            if self.is_visible(tag, timeout=1):
                return self.find(tag)
        for _ in range(max_swipes):
            size = self.driver.get_window_size()
            start_y = int(size["height"] * 0.3)
            end_y = int(size["height"] * 0.8)
            center_x = int(size["width"] * 0.5)
            self.driver.swipe(center_x, start_y, center_x, end_y, duration=300)
            time.sleep(0.3)
            if self.is_visible(tag, timeout=1):
                return self.find(tag)
        return self.wait_for(tag, timeout=5)

    def scroll_down_to(self, tag: str, max_swipes: int = 10) -> WebElement:
        """Swipe down through long forms until the element is visible."""
        element = self._scroll_into_view_by_uiautomator(tag)
        if element:
            return element
        for _ in range(max_swipes):
            if self.is_visible(tag, timeout=1):
                return self.find(tag)
            size = self.driver.get_window_size()
            start_y = int(size["height"] * 0.72)
            end_y = int(size["height"] * 0.22)
            center_x = int(size["width"] * 0.5)
            self.driver.swipe(center_x, start_y, center_x, end_y, duration=350)
            time.sleep(0.3)
        return self.wait_for(tag, timeout=5)

    def scroll_incrementally_to(self, tag: str, max_swipes: int = 60) -> WebElement:
        """Use bounded scroll steps so long LazyColumn content remains reachable."""
        element = self._scroll_into_view_by_uiautomator(tag)
        if element:
            return element
        for _ in range(max_swipes):
            element = self._find_by_test_tag(tag)
            if element:
                return element
            size = self.driver.get_window_size()
            center_x = int(size["width"] * 0.5)
            start_y = int(size["height"] * 0.66)
            end_y = int(size["height"] * 0.28)
            self.driver.swipe(center_x, start_y, center_x, end_y, duration=260)
            time.sleep(0.12)
        for _ in range(max_swipes // 2):
            element = self._find_by_test_tag(tag)
            if element:
                return element
            size = self.driver.get_window_size()
            center_x = int(size["width"] * 0.5)
            start_y = int(size["height"] * 0.28)
            end_y = int(size["height"] * 0.66)
            self.driver.swipe(center_x, start_y, center_x, end_y, duration=260)
            time.sleep(0.12)
        return self.wait_for(tag, timeout=5)
