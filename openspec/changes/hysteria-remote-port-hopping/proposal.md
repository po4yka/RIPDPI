# Change: Implement Hysteria2 remote port hopping

Task ID: `OUT-1792820412611017`

## Why

Imported Hysteria2 hopping profiles are rejected or lose their remote-port policy before activation. Native interval helpers do not schedule remote port changes. Users need one authenticated connection that continues to carry TCP and UDP across the configured ports.

## What Changes

- Import and preserve official Hysteria2 multi-port URI authority, sing-box port sets and intervals, Clash port sets and intervals, and existing RIPDPI bundle hopping extras.
- Validate and normalize one hopping model before activation. Preserve it through storage, relay selection, native JSON and chain-entry resolution.
- Rotate remote ports and protected local UDP sockets on a fixed or random schedule. Preserve the prior receive socket during each transition and keep Salamander active.
- Prove payload continuity with a pinned upstream Hysteria peer and independent forwarding receipts.
- BREAKING: invalid dormant hopping data and conflicting interval sources fail admission; no field is silently discarded or swapped. Old stored hopping strings are converted once to the validated model.

## Capabilities

### New Capabilities

- `hysteria-remote-port-hopping`: imported port-set policy, native socket rotation and continuous authenticated payload.

### Modified Capabilities

- None.

## Impact

- Runtime-state import and persistence, selector activation, service resolution, engine API JSON, native Hysteria transport and relay builders, backup classification and controlled peer acceptance.
- Existing server bundle range/fixed-interval schema stays unchanged. Producer qualification is coordinated separately after client acceptance.
- No production dependency or peer version upgrade. No live fleet, provider, private credential or physical-device action.
