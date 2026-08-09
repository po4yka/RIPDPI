# Change: Replace unmaintained paste proc-macro dependency

Task ID: `RST-1786264762917234`

## Why

Remove the RUSTSEC-2024-0436 waiver by upgrading or replacing the netlink-packet-core path that still pulls paste 1.0.15

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `replace-unmaintained-paste-proc-macro`: Replace unmaintained paste proc-macro dependency

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
