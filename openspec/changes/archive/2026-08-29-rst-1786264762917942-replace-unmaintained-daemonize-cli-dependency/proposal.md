# Change: Replace unmaintained daemonize CLI dependency

Task ID: `RST-1786264762917942`

## Why

Remove the RUSTSEC-2025-0069 waiver by replacing daemonize 0.5.0 in the local ripdpi-cli process mode while keeping the dependency outside every Android runtime graph

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `replace-unmaintained-daemonize-cli-dependency`: Replace unmaintained daemonize CLI dependency

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
