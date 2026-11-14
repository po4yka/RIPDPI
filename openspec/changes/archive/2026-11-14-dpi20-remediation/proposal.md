# Change: Fix DPI-20 resource and response contracts

Task ID: `ENG-1794637887235609`

## Why

DPI-20 can allocate an unbounded burst, lose a configured version on the JSON path, and treat a decoy reply as real-flow success.

## What Changes

- Limit each DPI-20 burst and the total count per chain to 64.
- Keep the selected version in UDP configuration and wire output.
- Keep unmatched QUIC decoy replies separate from real-flow success.
- Clarify header-only decoy semantics and preserve the current mixed wire fixture assertions.

## Capabilities

### New Capabilities

- `dpi20-contracts`: finite decoy work, version propagation, and real-response correlation.

### Modified Capabilities

- None.

## Impact

Kotlin model validation, ripdpi-proxy-config, ripdpi-desync, and ripdpi-proxy-runtime. No dependencies, schema, existing public API, stored-data migration, or release changes. The runtime reuses the current upstream compact QUIC response contexts. Existing configurations above the count limit are rejected.
