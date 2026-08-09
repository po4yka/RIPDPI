# Change: Replace unmaintained bincode transitive dependency

Task ID: `RST-1786264762917563`

## Why

Remove the RUSTSEC-2025-0141 waiver by upgrading or replacing the Arti tor-netdir to typed-index-collections path that still pulls bincode 2.0.1

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `replace-unmaintained-bincode-transitive-dependency`: Replace unmaintained bincode transitive dependency

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
