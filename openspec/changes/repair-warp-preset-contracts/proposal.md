# Change: Repair WARP preset contracts

Task ID: `TRN-1794466458131158`

## Why

The TUN-04 audit found that three WARP presets change real WireGuard packets
without a matching peer contract. Stored enable flags can disagree with runtime
state. The suggestion row calls an unselected preset an applied profile.

## What Changes

- Keep built-in WARP presets compatible with a plain WireGuard peer. Preserve
  separate prelude packets and the WARP reserved-byte convention.
- Use one enable contract across settings, imports, exports, and native mapping.
- Distinguish suggested and applied presets. Check and fix stale network hints
  if the local regression confirms the reported risk.
- Keep one implementation commit per confirmed finding. Record local evidence
  separately from Android and external provider acceptance.
- BREAKING: built-in imitation presets no longer change real packet H/S fields.
  These fields remain available in the explicit custom AWG configuration.

## Capabilities

### New Capabilities

- `warp-preset-contracts`: WARP prelude, enable state, and suggestion behavior.

### Modified Capabilities

- None. The generic AWG wire contract remains unchanged.

## Impact

- Kotlin settings, settings UI, native WARP runtime/probe paths, and their tests.
- Locale resources have one writer. No dependency, protobuf, JNI signature,
  quality baseline, or golden fixture changes are planned.
- External WARP registrations and physical-device probes need separate permission.
