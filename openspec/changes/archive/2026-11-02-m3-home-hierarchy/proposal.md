# Change: Give Home a clear status hierarchy on phone and tablet

Task ID: `UIX-1793534654918156`

## Why

The 2026-11-01 Material 3 audit found that Home hides its most important content. On the phone, an amber "System lockdown unknown" warning takes about 45% of the first screen above connection health, and a large empty area follows. When connected, the status is pushed out of view. On the tablet, Home is half empty, the Connect button is oversized in an 800px column, and the floating side column does not line up.

## What Changes

- Add a status hero that shows state, duration, and strategy.
- Collapse notices, including the system lockdown warning, into a compact banner or chip.
- Cap the width of the primary button.
- Remove dead area on the phone and the tablet.
- Re-record the phone and tablet goldens with owner approval.

## Capabilities

### New Capabilities

- `m3-home-hierarchy`: Give Home a clear status hierarchy on phone and tablet

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Home screen composables in `:app`.
- Roborazzi baselines for Home on phone and tablet.
- No protobuf, JNI, or wire change.
