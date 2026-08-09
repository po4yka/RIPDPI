# Change: Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS

Task ID: `RTE-1786264762917959`

## Why

Android 17 added a system-owned split-tunnel UI: VPN apps fire ACTIONVPNAPPEXCLUSIONSETTINGS and the OS persists user exclusions across reconnects. Wire this from RIPDPI settings so the per-app exclusion state lives in the OS instead of in-app, reducing the risk of exclusion loss on reconnect

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion`: Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS

### Modified Capabilities

- None.

## Impact

- Portfolio area: `routing`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
