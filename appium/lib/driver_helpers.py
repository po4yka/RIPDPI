"""Thin wait/find helpers shared across page objects and fixtures."""

from __future__ import annotations

import time
from typing import Callable

from appium.webdriver import WebElement
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException, WebDriverException
from selenium.webdriver.support.ui import WebDriverWait

from .launch_contract import APP_PACKAGE


def _resource_ids(tag: str) -> list[str]:
    if ":" in tag:
        return [tag]
    return [tag, f"{APP_PACKAGE}:id/{tag}"]


def _find_by_test_tag(driver, tag: str) -> WebElement | bool:
    for resource_id in _resource_ids(tag):
        try:
            return driver.find_element(AppiumBy.ID, resource_id)
        except (NoSuchElementException, WebDriverException):
            continue
    for resource_id in _resource_ids(tag):
        try:
            return driver.find_element(AppiumBy.XPATH, f'//*[@resource-id="{resource_id}"]')
        except NoSuchElementException:
            continue
    return False


def wait_for_element(driver, tag: str, timeout: int = 10) -> WebElement:
    """Wait until an element with the given test tag is present."""
    return WebDriverWait(driver, timeout).until(
        lambda source: _find_by_test_tag(source, tag),
        message=f"Timed out waiting for element '{tag}'",
    )


def is_visible(driver, tag: str, timeout: int = 3) -> bool:
    """Return True if the element becomes visible within timeout."""
    try:
        wait_for_element(driver, tag, timeout=timeout)
        return True
    except (TimeoutException, NoSuchElementException):
        return False


def wait_until(
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
