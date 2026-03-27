"""Workflow conftest: overrides root launch_app so app stays running between steps."""

from __future__ import annotations

import os
import time

import pytest

from lib.driver_helpers import wait_for_element
from lib.launch_contract import APP_PACKAGE, build_launch_args


# -- override the root autouse fixture (no-op) --------------------------------


@pytest.fixture(autouse=True)
def launch_app(driver, request):
    """No-op override: workflow tests manage their own app launch."""
    yield
    if hasattr(request.node, "rep_call") and request.node.rep_call.failed:
        os.makedirs("screenshots", exist_ok=True)
        name = request.node.name.replace(" ", "_")
        driver.save_screenshot(f"screenshots/{name}.png")


# -- workflow launch fixture ---------------------------------------------------


@pytest.fixture()
def workflow_app(driver, request):
    """Launch the app once for a workflow test using its automation marker."""
    marker = request.node.get_closest_marker("automation")
    params = marker.kwargs if marker else {}

    driver.execute_script("mobile: shell", {
        "command": "am",
        "args": ["force-stop", APP_PACKAGE],
    })
    time.sleep(0.5)

    args = build_launch_args(
        start_route=params.get("start_route", "home"),
        permission_preset=params.get("permission_preset", "granted"),
        service_preset=params.get("service_preset", "idle"),
        data_preset=params.get("data_preset", "clean_home"),
        reset_state=params.get("reset_state", True),
        disable_motion=params.get("disable_motion", True),
    )
    driver.execute_script("mobile: shell", {"command": "am", "args": args})

    route = params.get("start_route", "home")
    wait_for_element(driver, f"{route}-screen", timeout=15)

    return driver
