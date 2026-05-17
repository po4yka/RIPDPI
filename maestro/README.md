# Maestro Smoke Flows

These flows drive the installed debug app through stable Compose resource IDs. They keep the smoke pack close to real user navigation and avoid `pm clear`, onboarding, OS permission prompts, or live VPN consent. Use Appium or raw `adb` when a test needs launch-contract extras for a deterministic deep route.

## Prerequisites

- Install a debug build of RIPDPI on an emulator or device.
- Install the Maestro CLI. Repository runners accept `maestro` on `PATH`, `MAESTRO_BIN=/path/to/maestro`, or the default `~/.maestro/bin/maestro` install location.
- Keep the package name at `com.poyka.ripdpi`.

## Run

Run the full smoke pack:

```bash
maestro test maestro
```

Run a single flow:

```bash
maestro test maestro/01-cold-launch-home.yaml
```

## Flows

- `01-cold-launch-home.yaml`
- `02-settings-navigation.yaml`
- `03-advanced-settings-edit-save.yaml`
- `04-start-stop-configured-mode.yaml`

Additional local network lab flow skeletons live under `test-lab/maestro/`. Use them together with `test-lab/scripts/start-lab.sh` and the debug probe scripts when validating lab-backed diagnostics or VPN start/stop behavior.
