"""Python mirror of AutomationLaunchContract.kt constants and helpers."""

PREFIX = "com.poyka.ripdpi.automation"

ENABLED = f"{PREFIX}.ENABLED"
RESET_STATE = f"{PREFIX}.RESET_STATE"
START_ROUTE = f"{PREFIX}.START_ROUTE"
DISABLE_MOTION = f"{PREFIX}.DISABLE_MOTION"
PERMISSION_PRESET = f"{PREFIX}.PERMISSION_PRESET"
SERVICE_PRESET = f"{PREFIX}.SERVICE_PRESET"
DATA_PRESET = f"{PREFIX}.DATA_PRESET"

APP_PACKAGE = "com.poyka.ripdpi"
MAIN_ACTIVITY = f"{APP_PACKAGE}/.activities.MainActivity"


def build_launch_args(
    start_route: str = "home",
    permission_preset: str = "granted",
    service_preset: str = "idle",
    data_preset: str = "clean_home",
    reset_state: bool = True,
    disable_motion: bool = True,
) -> list[str]:
    """Build adb am-start argument list for the automation launch contract."""
    return [
        "start",
        "-n", MAIN_ACTIVITY,
        "--ez", ENABLED, "true",
        "--ez", RESET_STATE, str(reset_state).lower(),
        "--ez", DISABLE_MOTION, str(disable_motion).lower(),
        "--es", START_ROUTE, start_route,
        "--es", PERMISSION_PRESET, permission_preset,
        "--es", SERVICE_PRESET, service_preset,
        "--es", DATA_PRESET, data_preset,
    ]
