# External UI Automation

RIPDPI now exposes a debug-only launch contract so Maestro, Appium, and raw `adb` flows can boot the
app into deterministic UI states without depending on onboarding, biometric gating, OS dialogs, or
live VPN/native services.

## Scope

- The contract is available only in `debug` builds.
- Release builds ignore all automation extras.
- The selector surface is backed by [`RipDpiTestTags.kt`](../../app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt).
- Compose `testTag` values are exposed as Android resource IDs through the automation tree root.

## Documents

- [Selector Contract](./selector-contract.md)
- [Appium Readiness](./appium-readiness.md)

## Launch Contract

Use `adb shell am start` with explicit extras when you need a deterministic launch:

```bash
adb shell am start \
  -n com.poyka.ripdpi/.activities.MainActivity \
  --ez com.poyka.ripdpi.automation.ENABLED true \
  --ez com.poyka.ripdpi.automation.RESET_STATE true \
  --ez com.poyka.ripdpi.automation.DISABLE_MOTION true \
  --es com.poyka.ripdpi.automation.START_ROUTE advanced_settings \
  --es com.poyka.ripdpi.automation.PERMISSION_PRESET granted \
  --es com.poyka.ripdpi.automation.SERVICE_PRESET idle \
  --es com.poyka.ripdpi.automation.DATA_PRESET settings_ready
```

Supported preset values:

- `PERMISSION_PRESET`: `granted`, `notifications_missing`, `vpn_missing`, `battery_review`
- `SERVICE_PRESET`: `idle`, `connected_proxy`, `connected_vpn`, `live`
- `DATA_PRESET`: `clean_home`, `settings_ready`, `diagnostics_demo`

Intent extras take precedence over mirrored instrumentation arguments with the same keys.

## Maestro

Smoke flows live in [`maestro/`](../../maestro/README.md).

```bash
maestro test maestro
```

## Appium

Python + pytest smoke tests live in [`appium/`](../../appium/README.md). They use the same launch
contract as Maestro but provide programmatic page-object-driven assertions.

```bash
cd appium
pytest tests/ -v
```

## CI

The GitHub Actions `CI` workflow exposes manual inputs on `workflow_dispatch`:

- `run_maestro_smoke` -- Maestro smoke flows run after `connectedDebugAndroidTest` in the emulator lane.
- `run_appium_smoke` -- Appium smoke tests run after Maestro (if enabled) in the same emulator lane.
