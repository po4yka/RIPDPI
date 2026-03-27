"""Root conftest: Appium driver lifecycle and automation launch fixtures."""

from __future__ import annotations

import os
import time

import pytest
from appium.webdriver import Remote as AppiumDriver

from lib.capabilities import build_options
from lib.driver_helpers import wait_for_element
from lib.launch_contract import APP_PACKAGE, build_launch_args

APPIUM_URL = os.environ.get("APPIUM_URL", "http://127.0.0.1:4723")


# -- session-scoped driver ----------------------------------------------------


@pytest.fixture(scope="session")
def driver():
    """Create a single Appium session shared across all tests."""
    opts = build_options()
    drv = AppiumDriver(command_executor=APPIUM_URL, options=opts)
    yield drv
    drv.quit()


# -- per-test automation launch -----------------------------------------------


@pytest.fixture(autouse=True)
def launch_app(driver, request):
    """Force-stop, then launch the app with automation contract extras.

    Tests declare presets via the ``@pytest.mark.automation(...)`` marker.
    Defaults: start_route="home", data_preset="clean_home", service_preset="idle",
    permission_preset="granted".
    """
    marker = request.node.get_closest_marker("automation")
    params = marker.kwargs if marker else {}

    # Force-stop to guarantee a cold start.
    driver.execute_script("mobile: shell", {
        "command": "am",
        "args": ["force-stop", APP_PACKAGE],
    })
    time.sleep(0.5)

    # Launch with automation contract.
    args = build_launch_args(
        start_route=params.get("start_route", "home"),
        permission_preset=params.get("permission_preset", "granted"),
        service_preset=params.get("service_preset", "idle"),
        data_preset=params.get("data_preset", "clean_home"),
        reset_state=params.get("reset_state", True),
        disable_motion=params.get("disable_motion", True),
    )
    driver.execute_script("mobile: shell", {"command": "am", "args": args})

    # Wait for the expected screen to render.
    route = params.get("start_route", "home")
    wait_for_element(driver, f"{route}-screen", timeout=15)

    yield

    # Screenshot on failure for debugging.
    if hasattr(request.node, "rep_call") and request.node.rep_call.failed:
        os.makedirs("screenshots", exist_ok=True)
        name = request.node.name.replace(" ", "_")
        driver.save_screenshot(f"screenshots/{name}.png")


# -- pytest hooks -------------------------------------------------------------


@pytest.hookimpl(tryfirst=True, hookwrapper=True)
def pytest_runtest_makereport(item, call):
    """Attach call report to the item so the launch_app fixture can check it."""
    outcome = yield
    rep = outcome.get_result()
    setattr(item, f"rep_{rep.when}", rep)
