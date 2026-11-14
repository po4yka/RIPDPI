# Change: Correct DPI-24 resource and response contracts

Task ID: `ENG-1794637907795927`

## Why

The audit found unbounded packet copies, success accounting for decoy replies, and incorrect names. They affect stability and diagnostic accuracy.

## What Changes

- Bound DPI-24 repetition to 64 per chain in Kotlin, Rust config, and planning.
- Correlate first QUIC success with a valid supported Initial or Retry.
- Reproduce and correct early probe completion on unrelated responses.
- Correct names and documents; retain saved IDs, old aliases, and packet bytes.
- Previously accepted unsafe counts above 64 become errors. No schema change.

## Capabilities

### New Capabilities

- `dpi24-contract-safety`: bounded allocation, correlated accounting, and correct names.

### Modified Capabilities

- None.

## Impact

Kotlin model/settings/app and Rust config/desync/runtime/diagnostics crates. No dependencies or schema versions change.
