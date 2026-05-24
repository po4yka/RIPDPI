# Maestro Smoke Flows

These flows drive the installed debug app through stable Compose resource IDs. They keep the smoke pack close to real user navigation and avoid `pm clear`, onboarding, OS permission prompts, or live VPN consent in the always-safe path. The repository runner also has route-preset flows that launch the debug automation contract before Maestro starts assertions, which covers deep screens, seeded reports, permission banners, and connected service states without text selectors.

## Prerequisites

- Install a debug build of RIPDPI on an emulator or device.
- Install the Maestro CLI. Repository runners accept `maestro` on `PATH`, `MAESTRO_BIN=/path/to/maestro`, or the default `~/.maestro/bin/maestro` install location.
- Keep the package name at `com.poyka.ripdpi`.

## Run

Run the full smoke pack:

```bash
bash scripts/ci/run-maestro-smoke.sh
```

Run a single flow:

```bash
maestro test maestro/01-cold-launch-home.yaml
```

Run only the visible-navigation flows and skip route-preset coverage:

```bash
RUN_MAESTRO_ROUTE_FLOWS=0 bash scripts/ci/run-maestro-smoke.sh
```

Run the live lab-backed flows after starting the local network lab:

```bash
RUN_MAESTRO_LAB_FLOWS=1 bash scripts/ci/run-maestro-smoke.sh
```

Run the complex journey tier:

```bash
RUN_MAESTRO_COMPLEX_FLOWS=1 bash scripts/ci/run-maestro-smoke.sh
```

## Flows

- `01-cold-launch-home.yaml`
- `02-settings-navigation.yaml`
- `03-advanced-settings-edit-save.yaml`
- `04-start-stop-configured-mode.yaml`
- `05-top-tab-navigation.yaml`
- `06-config-feature-coverage.yaml`
- `07-dns-settings-coverage.yaml`
- `08-settings-secondary-screens.yaml`
- `09-advanced-control-coverage.yaml`
- `10-diagnostics-ui-coverage.yaml`

Route-preset flows live under `maestro/route/` and are launched by `scripts/ci/run-maestro-smoke.sh` through `adb shell am start -a com.poyka.ripdpi.automation.LAUNCH` before each Maestro assertion file. They cover history/logs, permission banners, connected proxy/VPN state, and seeded diagnostics reports.

Complex journey flows live under `maestro/complex/` and are opt-in through `RUN_MAESTRO_COMPLEX_FLOWS=1`. They cover onboarding to first connect, config preset/edit/connect, DNS round-trip, diagnostics report drill-down, permission repair, activation-window edits, logs/history filters, biometric PIN fallback, and background guidance dismissal.

Additional local network lab flows live under `test-lab/maestro/`. Use them together with `test-lab/scripts/start-lab.sh` and the debug probe scripts when validating lab-backed diagnostics or VPN start/stop behavior. `test-lab/maestro/complex-proxy-vpn-reconnect.yaml` covers the lab-backed proxy/VPN reconnect journey and runs only with `RUN_MAESTRO_LAB_FLOWS=1`.
