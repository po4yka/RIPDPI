# Change: Preserve Lua state for active tunnel flows

Task ID: `RST-1790431269626912`

## Why

The Lua strategy engine silently evicts the oldest connection table after 1024 distinct flows. A tunnel can have more than 1024 active flows, so a later packet can observe reset state while its flow is still active. The production TUN path does not call the existing Lua close operation.

## What Changes

- A full state table reports a strategy error for a new flow without deleting an admitted flow's state. The existing `on_fail` policy handles that error.
- TUN flow termination releases state so new flows can use freed capacity.
- Regression tests cover concurrent flows, termination, and reuse through the TUN integration.

## Capabilities

### New Capabilities

- `lua-flow-state-lifecycle`: Preserve admitted flow state and reclaim it at a known flow end.

### Modified Capabilities

- None.

## Impact

- Rust Lua strategy, strategy registry contract, TUN interception and flow lifecycle integration, plus tests and the affected API snapshot. No new dependency or stored-data format.
