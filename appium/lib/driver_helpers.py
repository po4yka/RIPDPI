"""Thin wait/find helpers shared across page objects and fixtures."""

from __future__ import annotations

from appium.webdriver import WebElement
from appium.webdriver.common.appiumby import AppiumBy
from selenium.common.exceptions import NoSuchElementException, TimeoutException
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.support.ui import WebDriverWait

from .launch_contract import APP_PACKAGE


def _resource_id(tag: str) -> str:
    if ":" in tag:
        return tag
    return f"{APP_PACKAGE}:id/{tag}"


def wait_for_element(driver, tag: str, timeout: int = 10) -> WebElement:
    """Wait until an element with the given test tag is present."""
    locator = (AppiumBy.ID, _resource_id(tag))
    return WebDriverWait(driver, timeout).until(
        EC.presence_of_element_located(locator),
        message=f"Timed out waiting for element '{tag}'",
    )


def is_visible(driver, tag: str, timeout: int = 3) -> bool:
    """Return True if the element becomes visible within timeout."""
    try:
        wait_for_element(driver, tag, timeout=timeout)
        return True
    except (TimeoutException, NoSuchElementException):
        return False
