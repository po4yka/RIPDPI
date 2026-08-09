# Change: Adopt process-based per-package routing via Xray TUN routeOnly

Task ID: `RTE-1786264762917255`

## Why

reference Android implementation 2.1.0 (2026-04-17) shipped per-package routing via Xray TUN with routeOnly enabled. Adopt the same pattern so RIPDPI users can route selected platform-detection-positive apps directly while everything else goes through VLESS

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `adopt-process-based-per-package-routing-via-xray-tun-routeonly`: Adopt process-based per-package routing via Xray TUN routeOnly

### Modified Capabilities

- None.

## Impact

- Portfolio area: `routing`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
