# Change: Bound optional QUIC SNI split work

Task ID: `ENG-1794637894202698`

## Why

Excessive repetition counts can exhaust memory. An optional datagram error can prevent delivery of the original payload.

## What Changes

- Reject excessive individual and aggregate DPI-18 counts.
- Bound planner allocations for direct Rust models.
- Skip optional packets that exceed the input size.
- Preserve the original after recoverable local send failures.
- Compatibility: excessive configurations are rejected; wire and storage schemas stay unchanged.

## Capabilities

### New Capabilities

- `quic-sni-prelude-safety`: bounded optional work and original delivery.

### Modified Capabilities

- None.

## Impact

Kotlin model, native configuration, planner and UDP adapter. No new dependencies or wire formats.
