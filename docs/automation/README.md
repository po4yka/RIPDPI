# External UI Automation

RIPDPI now exposes a debug-only launch contract so Appium and raw `adb` flows can boot the app into deterministic UI states without depending on onboarding, biometric gating, OS dialogs, or live VPN/native services. Maestro smoke flows use the same stable selector surface, but drive visible UI navigation because Maestro's Android launch argument handling is less suitable for route-specific contract assertions.

## Scope

- The contract is available only in `debug` builds.
- Release builds ignore all automation extras.
- The selector surface is backed by [`RipDpiTestTags.kt`](../../app/src/main/kotlin/com/poyka/ripdpi/ui/testing/RipDpiTestTags.kt).
- Compose `testTag` values are exposed as Android resource IDs through the automation tree root.

## Documents

- [Selector Contract](./selector-contract.md)
- [Appium Readiness](./appium-readiness.md)
- [Local Network Test Lab](../../test-lab/README.md)

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
- `DATA_PRESET`: `clean_home`, `settings_ready`, `diagnostics_demo`, `diagnostics_report_demo`, `biometric_locked`, `biometric_locked_with_pin`

Intent extras take precedence over mirrored instrumentation arguments with the same keys.

## Maestro

Smoke flows live in [`maestro/`](../../maestro/README.md).

```bash
bash scripts/ci/run-maestro-smoke.sh
```

Repository runners also accept `MAESTRO_BIN=/path/to/maestro` and Maestro's default `~/.maestro/bin/maestro` install location, which is useful when the CLI installer did not modify the shell `PATH`.

The committed Maestro pack starts from the installed app and navigates through visible controls by resource ID for the always-safe flows. `scripts/ci/run-maestro-smoke.sh` additionally launches route-preset flows with `adb shell am start -a com.poyka.ripdpi.automation.LAUNCH`, which lets Maestro cover history, logs, permission banners, connected service states, and seeded diagnostics reports while still asserting stable resource IDs. Set `RUN_MAESTRO_ROUTE_FLOWS=0` to run only the visible-navigation flows, `RUN_MAESTRO_COMPLEX_FLOWS=1` to include complex stateful journeys, or `RUN_MAESTRO_LAB_FLOWS=1` to include the live network lab flows under `test-lab/maestro/`.

## Appium

Python + pytest smoke tests live in [`appium/`](../../appium/README.md). They use the debug launch contract for route and fixture presets, then provide programmatic page-object-driven assertions.

```bash
cd appium
pytest tests/ -v
```

In CI (and to reproduce it locally) run `bash scripts/ci/run-appium-smoke.sh`, which prepares the environment before invoking pytest — mirroring the `run-maestro-smoke.sh` wrapper above.

## Debug Network Probe

Debug builds also expose a machine-readable network probe receiver for lab and device smoke checks. Prefer the wrapper scripts because they choose the current host profile, DNS port, package name, and output path:

```bash
./test-lab/scripts/start-lab.sh --profile emulator
./test-lab/scripts/adb-install-debug.sh
./test-lab/scripts/adb-run-probe-emulator.sh --mode diagnostics
./test-lab/scripts/stop-lab.sh
```

The underlying action is `com.poyka.ripdpi.DEBUG_PROBE`. It is declared only in `app/src/debug/AndroidManifest.xml`, and the receiver writes JSON to the app's external files directory before the script pulls it into `test-lab/artifacts/`.

## CI

The GitHub Actions `CI` workflow exposes manual inputs on `workflow_dispatch`:

- `run_maestro_smoke` -- Maestro smoke flows run after `connectedDebugAndroidTest` in the emulator lane.
- `run_appium_smoke` -- Appium smoke tests run after Maestro (if enabled) in the same emulator lane.
