# Change: Measure selector health through the applied VPN

Task ID: `VPN-1795255851577459`

## Why

A fresh candidate can transfer a complete response while the VPN session that carries app traffic is stalled. Using candidate health for both observations can prevent the selector from checking a reserve.

## What Changes

- Verify the configured payload through the applied VPN route for the current member.
- Use isolated candidate probes for reserves.
- Discard a pass when the measured VPN route, applied member or physical network changes.

## Capabilities

### New Capabilities

- `selector-live-vpn-evidence` — Measure the running VPN separately from reserve candidates.

### Modified Capabilities

- None.

## Impact

The change affects payload-based selection in VPN mode and the owned Android transport fixture.
