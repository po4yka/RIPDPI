# Change: Distinguish skipped strategy steps

Task ID: `RST-1790428851570062`

## Why

A matching UDP or IPv6 strategy can decline a packet without adding an action. The registry currently treats that successful return as terminal, so later configured steps do not run. A change based only on the action count would also change the meaning of a successful Lua no-op.

## What Changes

- A skipped strategy step lets the registry try the next configured step.
- A handled step remains terminal, including a successful Lua no-op and explicit Lua verdicts.
- BREAKING: the Rust `DesyncStrategy::plan` return type reports handled or skipped explicitly.

## Capabilities

### New Capabilities

- `strategy-step-outcomes`: Distinguish a handled step from one that declines the current packet.

### Modified Capabilities

- None.

## Impact

- Rust strategy contract, strategy implementations, registry, registry tests, and the checked-in Rust API snapshot.
- The TUN egress caller continues to consume the final registry verdict.
