# Change: Repair TLS version and entropy contracts

Task ID: `ENG-1794672016726805`

## Why

DPI-30 found real-stream corruption, incorrect combined targets, zero-budget violation, TLS composition failures, and misleading documentation.

## What Changes

- Shape fake bytes without adding bytes to real TCP payloads.
- Respect joint entropy targets and byte budgets.
- Preserve TLS minor overrides for no-SNI and multi-record inputs.
- Correct record-version and popcount units documentation.
- No schema or dependency changes. Wire mutation remains opt-in.

## Capabilities

### New Capabilities

- `dpi30-payload-contracts`: entropy placement and TLS header mutation contracts.

### Modified Capabilities

- None.

## Impact

Rust packets, desync planning, desync runtime, and native documentation. No required backend, root activation, or production change.
