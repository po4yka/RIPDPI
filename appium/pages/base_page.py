"""Base page object with shared interaction helpers."""

from __future__ import annotations

import time
from typing import TYPE_CHECKING, Callable

from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import (
    NoSuchElementException, StaleElementReferenceException, TimeoutException, WebDriverException,
)
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

    def is_checked(self, tag: str) -> bool:
        """Read a switch or radio state; reject missing accessibility state."""
        element = self.wait_for(tag)
        if element.get_attribute("checkable") != "true":
            raise ValueError(f"Element {tag!r} does not expose a checked state")
        value = element.get_attribute("checked")
        if value not in ("true", "false"):
            raise ValueError(f"Invalid checked state for {tag!r}: {value!r}")
        return value == "true"

    def is_selected(self, tag: str) -> bool:
        """Read Compose selection exposed as checked or selected on Android."""
        element = self.wait_for(tag)
        checkable = element.get_attribute("checkable")
        if checkable not in ("true", "false"):
            raise ValueError(f"Invalid checkable state for {tag!r}: {checkable!r}")
        attribute = "checked" if checkable == "true" else "selected"
        value = element.get_attribute(attribute)
        if value not in ("true", "false"):
            raise ValueError(f"Invalid {attribute} state for {tag!r}: {value!r}")
        return value == "true"

    def get_text(self, tag: str, timeout: int = 10) -> str:
        """Read element text, including labels in an unmerged container."""
        el = self.wait_for(tag, timeout=timeout)
        own_text = el.text or el.get_attribute("text")
        if own_text:
            return own_text
        labels = el.find_elements(AppiumBy.XPATH, './/*[@text != ""]')
        return " ".join(label.get_attribute("text") for label in labels)

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

    # Opt-in scrolling for screens that contain more than one scroll axis.
    def _scoped_target(self, tag: str, container_tag: str, fully_visible: bool):
        container = self.find(container_tag)
        target = self._find_by_test_tag(tag)
        if not target or not target.is_displayed() or not target.is_enabled():
            return None
        if target.get_attribute("resource-id") not in self._resource_ids(tag):
            return None
        area, rect = container.rect, target.rect
        left, top = area["x"], area["y"]
        right, bottom = left + area["width"], top + area["height"]
        x, y = rect["x"], rect["y"]
        end_x, end_y = x + rect["width"], y + rect["height"]
        if rect["width"] <= 0 or rect["height"] <= 0:
            return None
        if fully_visible:
            inside = left < x and top < y and end_x < right and end_y < bottom
        else:
            inside = left < (x + end_x) / 2 < right and top < (y + end_y) / 2 < bottom
        return target if inside else None

    def _scroll_container_once(self, container_tag: str, direction: str) -> None:
        area = self.find(container_tag).rect
        center_x = area["x"] + area["width"] // 2
        upper_y = area["y"] + int(area["height"] * 0.2)
        lower_y = area["y"] + int(area["height"] * 0.8)
        start_y, end_y = (lower_y, upper_y) if direction == "down" else (upper_y, lower_y)
        # W3C touch actions work with Compose here; scrollGesture reports false
        # without moving this body. Both endpoints stay clear of tabs and bars.
        self.driver.swipe(center_x, start_y, center_x, end_y, duration=600)

    def scroll_to_in_container(
        self, tag: str, container_tag: str, max_swipes: int = 5,
        fully_visible: bool = True,
    ):
        """Find an exact target using bounded gestures inside one tagged body."""
        self.wait_for(container_tag)
        for direction in ("down", "up"):
            for _ in range(max_swipes):
                try:
                    target = self._scoped_target(tag, container_tag, fully_visible)
                    if target:
                        return target
                    area = self.find(container_tag).rect
                    target = self._find_by_test_tag(tag)
                    gesture_direction = direction
                    if target:
                        rect = target.rect
                        if rect["y"] <= area["y"]:
                            gesture_direction = "up"
                        elif rect["y"] + rect["height"] >= area["y"] + area["height"]:
                            gesture_direction = "down"
                    self._scroll_container_once(container_tag, gesture_direction)
                except (NoSuchElementException, StaleElementReferenceException):
                    continue
        target = self._scoped_target(tag, container_tag, fully_visible)
        if target:
            return target
        raise TimeoutException(f"Exact target {tag!r} is not visible inside {container_tag!r}")

    def tap_in_container(self, tag: str, container_tag: str, fully_visible: bool = True) -> None:
        """Re-find and validate the exact target after scrolling, then click once."""
        self.scroll_to_in_container(tag, container_tag, fully_visible=fully_visible)
        target = self._scoped_target(tag, container_tag, fully_visible)
        if not target:
            raise TimeoutException(f"Exact target {tag!r} moved before click in {container_tag!r}")
        target.click()
