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

# Valid values from AutomationLaunchContract.kt (source of truth).
VALID_PERMISSION_PRESETS = {
    "granted", "notifications_missing", "vpn_missing", "battery_review",
}
VALID_SERVICE_PRESETS = {
    "idle", "connected_proxy", "connected_vpn", "live",
}
VALID_DATA_PRESETS = {
    "clean_home", "settings_ready", "diagnostics_demo",
}
VALID_ROUTES = {
    "onboarding", "home", "config", "diagnostics", "history", "logs",
    "settings", "mode_editor", "dns_settings", "advanced_settings",
    "biometric_prompt", "app_customization", "about", "data_transparency",
}


def build_launch_args(
    start_route: str = "home",
    permission_preset: str = "granted",
    service_preset: str = "idle",
    data_preset: str = "clean_home",
    # Intentionally True (test-friendly); Kotlin defaults to false for production.
    reset_state: bool = True,
    disable_motion: bool = True,
) -> list[str]:
    """Build adb am-start argument list for the automation launch contract."""
    if start_route not in VALID_ROUTES:
        raise ValueError(
            f"Unknown start_route {start_route!r}; valid: {sorted(VALID_ROUTES)}"
        )
    if permission_preset not in VALID_PERMISSION_PRESETS:
        raise ValueError(
            f"Unknown permission_preset {permission_preset!r}; "
            f"valid: {sorted(VALID_PERMISSION_PRESETS)}"
        )
    if service_preset not in VALID_SERVICE_PRESETS:
        raise ValueError(
            f"Unknown service_preset {service_preset!r}; "
            f"valid: {sorted(VALID_SERVICE_PRESETS)}"
        )
    if data_preset not in VALID_DATA_PRESETS:
        raise ValueError(
            f"Unknown data_preset {data_preset!r}; "
            f"valid: {sorted(VALID_DATA_PRESETS)}"
        )
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
