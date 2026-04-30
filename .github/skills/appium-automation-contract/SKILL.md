---
name: appium-automation-contract
description: Appium launch contract, start routes, state presets, and wrong-screen test debugging.
---

# Appium Automation Contract

The automation contract launches the app directly to a specific screen with controlled state, bypassing normal navigation. It is the foundation for every Appium test.

**Source of truth:** Kotlin `AutomationLaunchContract.kt` in `app/src/main/kotlin/com/poyka/ripdpi/automation/`.
**Python mirror:** `appium/lib/launch_contract.py`.

## Launch Flow

```
conftest.py::launch_app (autouse fixture)
  1. Read @pytest.mark.automation(...) kwargs from test
  2. adb am force-stop com.poyka.ripdpi
  3. sleep 0.5s (process teardown settle)
  4. adb am start -n com.poyka.ripdpi/.activities.MainActivity \
       --ez com.poyka.ripdpi.automation.ENABLED true \
       --es com.poyka.ripdpi.automation.START_ROUTE {route} \
       ... (remaining extras)
  5. wait_for_element(driver, "{route}-screen", timeout=15)
  6. yield (test runs)
  7. Screenshot on failure -> screenshots/{test_name}.png
```

## Intent Extras

| Python Constant | Extra Key | Type | Default | Purpose |
|----------------|-----------|------|---------|---------|
| `ENABLED` | `...automation.ENABLED` | bool | `true` | Activate automation mode |
| `RESET_STATE` | `...automation.RESET_STATE` | bool | `true` | Clear persisted state before launch |
| `DISABLE_MOTION` | `...automation.DISABLE_MOTION` | bool | `true` | Disable Compose animations |
| `START_ROUTE` | `...automation.START_ROUTE` | string | `"home"` | Target screen |
| `PERMISSION_PRESET` | `...automation.PERMISSION_PRESET` | string | `"granted"` | Simulated permission state |
| `SERVICE_PRESET` | `...automation.SERVICE_PRESET` | string | `"idle"` | Simulated service state |
| `DATA_PRESET` | `...automation.DATA_PRESET` | string | `"clean_home"` | Pre-populated data fixtures |

All extras are prefixed with `com.poyka.ripdpi.automation.`.

## Preset Reference

### start_route

| Route | Screen Resource ID | Typical data_preset |
|-------|-------------------|---------------------|
| `home` | `home-screen` | `clean_home` |
| `onboarding` | `onboarding-screen` | `clean_home` |
| `config` | `config-screen` | `settings_ready` |
| `diagnostics` | `diagnostics-screen` | `diagnostics_demo` |
| `history` | `history-screen` | `settings_ready` |
| `logs` | `logs-screen` | `settings_ready` |
| `settings` | `settings-screen` | `settings_ready` |
| `mode_editor` | `mode_editor-screen` | `settings_ready` |
| `dns_settings` | `dns_settings-screen` | `settings_ready` |
| `advanced_settings` | `advanced_settings-screen` | `settings_ready` |
| `about` | `about-screen` | `settings_ready` |
| `data_transparency` | `data_transparency-screen` | `settings_ready` |
| `app_customization` | `app_customization-screen` | `settings_ready` |

### permission_preset

| Value | Simulates |
|-------|-----------|
| `granted` | All permissions granted (default) |
| `vpn_missing` | VPN permission not granted |
| `notifications_missing` | Notification permission not granted |
| `battery_review` | Battery optimization not disabled |

### service_preset

| Value | Simulates |
|-------|-----------|
| `idle` | Service not running (default) |
| `connected_proxy` | Proxy service connected |
| `connected_vpn` | VPN service connected |
| `live` | Live connection with real service |

### data_preset

| Value | Simulates |
|-------|-----------|
| `clean_home` | Fresh install state (default) |
| `settings_ready` | Settings populated, configs available |
| `diagnostics_demo` | Demo diagnostic data pre-loaded |

## Marker Usage

```python
# Minimal -- all defaults (home screen, granted, idle, clean_home)
@pytest.mark.automation()
def test_home_loads(driver): ...

# Deep-link to sub-screen with populated data
@pytest.mark.automation(
    start_route="dns_settings",
    data_preset="settings_ready",
)
def test_dns_plain_save(driver): ...

# Permission-denied scenario
@pytest.mark.automation(
    start_route="home",
    permission_preset="vpn_missing",
)
def test_vpn_permission_banner(driver): ...

# Connected state testing
@pytest.mark.automation(
    start_route="home",
    service_preset="connected_vpn",
    data_preset="clean_home",
)
def test_connected_vpn_stats(driver): ...
```

All parameters are keyword-only. Omitted params use defaults shown in the Intent Extras table.

## Adding a New Route

1. Add route string to Kotlin `Route` sealed class
2. Add handling in `AutomationLaunchContract.kt` to navigate to the new screen
3. Set `Modifier.testTag("{route}-screen")` on the screen's root composable
4. Use `@pytest.mark.automation(start_route="{route}")` in the Python test
5. The `conftest.py` wait (`wait_for_element(driver, f"{route}-screen", timeout=15)`) works automatically

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| Forgetting `reset_state=True` | Previous test's data leaks in. Default is `True` -- only set `False` if intentionally preserving state. |
| Wrong `data_preset` for the screen | Screen renders empty or missing elements. Match route to its typical preset (see table above). |
| Omitting `disable_motion=True` | Animations cause timing issues, especially in CI. Default is `True` -- only set `False` to test animations. |
| `start_route` value not in Kotlin `Route` | App silently falls back to home screen. Verify the route exists in `Route.kt`. |
| Screen resource-id doesn't match `{route}-screen` | `wait_for_element` times out on launch. The convention is `{route}-screen`. |
| Testing connected state with `service_preset="idle"` | Stats/metrics cards won't be visible. Use `connected_vpn` or `connected_proxy`. |

## See Also

- `.github/skills/appium-test-authoring/SKILL.md` -- How to write tests using the automation marker
- `.github/skills/appium-test-debug/SKILL.md` -- Troubleshooting when launch fails
- `.github/skills/android-device-debug/SKILL.md` -- Raw ADB commands for device interaction
