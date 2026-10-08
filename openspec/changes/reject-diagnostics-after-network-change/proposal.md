# Change: Reject diagnostic recommendations after a network change

Task ID: `DGN-1791479038917244`

## Why

A scan can continue after a physical network switch and record its results as validated policy for the original network. A final fingerprint check alone misses A to B to A transitions.

## What Changes

- Keep mixed-network measurements for inspection, but remove their recommendation authority.
- Do not apply resolver overrides or remember policy, DNS, edge, or capability evidence from an invalid scan scope.
- Use a physical callback epoch that survives normal RIPDPI VPN stop and start.

## Capabilities

### New Capabilities

- `diagnostics-network-scope`: require stable physical network evidence before diagnostic recommendations have authority.

### Modified Capabilities

- None.

## Impact

- Kotlin core:data:model, core:service, and core:diagnostics.
- No new dependencies, JNI, wire, protobuf, settings, or database schema changes.
