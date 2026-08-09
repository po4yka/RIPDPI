# Change: Epic - Fail-closed Android VPN policy engine

Task ID: `EPC-1786264762917557`

## Why

Make RIPDPI a fail-closed policy-first Android tunneled outbound profile, not just a GUI for imported proxy links. The app should eliminate the common failure classes in existing clients: incomplete policy bundles, DNS and IPv6 leaks, weak kill-switch UX, shared subscriptions, manual-only failover, unsafe logs, and untested VPN lifecycle behavior

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `epic-fail-closed-android-vpn-policy-engine`: Epic - Fail-closed Android VPN policy engine

### Modified Capabilities

- None.

## Impact

- Portfolio area: `epic`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
