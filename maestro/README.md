# Maestro Smoke Flows

These flows target the debug automation contract instead of relying on `pm clear`, onboarding, OS
permission prompts, or live VPN consent.

## Prerequisites

- Install a debug build of RIPDPI on an emulator or device.
- Install the Maestro CLI.
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

Additional local network lab flow skeletons live under `test-lab/maestro/`.
Use them together with `test-lab/scripts/start-lab.sh` and the debug probe
scripts when validating lab-backed diagnostics or VPN start/stop behavior.
