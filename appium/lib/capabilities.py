"""UiAutomator2 desired capabilities for RIPDPI debug builds."""

from appium.options import UiAutomator2Options

from .launch_contract import APP_PACKAGE, MAIN_ACTIVITY


def build_options(
    device_name: str = "Android",
    no_reset: bool = True,
    new_command_timeout: int = 120,
) -> UiAutomator2Options:
    """Return UiAutomator2Options matching docs/automation/appium-readiness.md."""
    opts = UiAutomator2Options()
    opts.platform_name = "Android"
    opts.device_name = device_name
    opts.app_package = APP_PACKAGE
    # UiAutomator2 expects the shorthand form with leading dot.
    opts.app_activity = MAIN_ACTIVITY.split("/")[-1]
    opts.no_reset = no_reset
    opts.new_command_timeout = new_command_timeout
    opts.auto_grant_permissions = False  # handled by automation contract
    return opts
