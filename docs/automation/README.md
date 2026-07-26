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
  --es com.poyka.ripdpi.automation.DATA_PRESET settings_ready \
  --es com.poyka.ripdpi.automation.THEME dark
```

Supported preset values:

- `PERMISSION_PRESET`: `granted`, `notifications_missing`, `vpn_missing`, `battery_review`
- `SERVICE_PRESET`: `idle`, `connected_proxy`, `connected_vpn`, `live`
- `DATA_PRESET`: `clean_home`, `settings_ready`, `diagnostics_demo`, `diagnostics_report_demo`, `biometric_locked`, `biometric_locked_with_pin`
- `THEME`: `system`, `light`, `dark`

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

## UI/UX PDF Audit

The guide generator can produce a route/state UI audit PDF from a connected phone. The audit spec at [`scripts/guide/specs/ui-ux-audit.yaml`](../../scripts/guide/specs/ui-ux-audit.yaml) launches each route through the debug automation contract, captures optimized screenshots, saves a UiAutomator XML dump per page, checks the expected screen root and required selectors, writes `ui-audit.json`, writes Mermaid flow source to `user-flow.mmd`, renders sectioned flow SVGs, and compiles those artifacts into the final Typst PDF.

The report is intended to describe the current captured UI state, not prescribe design changes. Keep page descriptions in the spec neutral and present-tense; avoid recommendation language such as "should expose" or "needs to".

Prerequisites:

- A connected Pixel-class Android device with USB debugging enabled. The current audit target is a Pixel 7, but any device reachable by `adb` can be used for development checks.
- A debug build installed from this checkout. Release builds ignore the automation extras.
- `typst` on `PATH`.
- Python dependencies from [`scripts/guide/requirements.txt`](../../scripts/guide/requirements.txt).

Install the app and generate the full report:

```bash
SERIAL="$(adb devices | awk '/device$/{print $1; exit}')"
./gradlew :app:installGithubFullDebug -Pandroid.injected.device.serial="$SERIAL" --console=plain
python3 -m venv build/guide-test-venv
build/guide-test-venv/bin/python -m pip install -r scripts/guide/requirements.txt
build/guide-test-venv/bin/python scripts/guide/generate_guide.py --spec scripts/guide/specs/ui-ux-audit.yaml --device "$SERIAL" --strict-audit --output build/guide/ripdpi-ui-ux-audit.pdf
```

The generator captures every spec page twice, once with `THEME=dark` and once with `THEME=light`, then places the paired screenshots next to each other in the PDF. It also pre-grants runtime permissions that would otherwise produce Android system dialogs during capture (`CAMERA`, notifications, and coarse location), drains any already-visible permission dialog, enables Android demo mode for a stable status bar, and disables demo mode at the end.

Generated artifacts are written under `build/guide/`:

- `ripdpi-ui-ux-audit.pdf` -- final report with cover, contents, audit summary, flow diagrams, and dark/light screen captures.
- `screenshots/dark/` and `screenshots/light/` -- raw optimized device screenshots.
- `framed/dark/` and `framed/light/` -- screenshots composited into the Pixel device frame used by the PDF.
- `ui-dumps/dark/` and `ui-dumps/light/` -- UiAutomator XML dumps for selector debugging.
- `ui-audit.json` -- selector reachability results, UI-tree counters, and route exclusions with their prerequisites and reasons.
- `guide-data.json` -- Typst input data.
- `user-flow.mmd`, `user-flow.svg`, and `user-flow-*.svg` -- generated flow source and rendered diagram sections.

For iteration:

```bash
SERIAL="$(adb devices | awk '/device$/{print $1; exit}')"
build/guide-test-venv/bin/python scripts/guide/generate_guide.py --spec scripts/guide/specs/ui-ux-audit.yaml --pages home_idle,settings,scanner --device "$SERIAL" --output build/guide/ripdpi-ui-ux-audit-smoke.pdf
build/guide-test-venv/bin/python scripts/guide/generate_guide.py --spec scripts/guide/specs/ui-ux-audit.yaml --skip-capture --output build/guide/ripdpi-ui-ux-audit.pdf
build/guide-test-venv/bin/python scripts/guide/generate_guide.py --spec scripts/guide/specs/ui-ux-audit.yaml --no-frame --pages scanner --device "$SERIAL" --output build/guide/ripdpi-ui-ux-audit-scanner.pdf
```

Use `--pages` for a focused live pass, `--no-frame` for faster layout/debug iteration, and `--skip-capture` when only the Typst template, palette, captions, or flow diagrams changed. Add `--strict-audit` to completion checks so any failed theme, missing required selector, absent audit result, or missing screenshot exits non-zero. A skipped capture reuses the cached screenshots, `ui-audit.json`, and generated diagram inputs from `build/guide/`.

Before committing generator or spec changes, run:

```bash
build/guide-test-venv/bin/python -m unittest scripts.tests.test_generate_guide
./gradlew :app:testGithubFullDebugUnitTest --tests com.poyka.ripdpi.automation.AutomationLaunchContractTest --tests com.poyka.ripdpi.automation.DebugAutomationControllerTest --console=plain
```

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

- `run_maestro_smoke` -- Maestro smoke flows run after the flavor-qualified Github Full instrumentation task in the emulator lane.
- `run_appium_smoke` -- Appium smoke tests run after Maestro (if enabled) in the same emulator lane.
