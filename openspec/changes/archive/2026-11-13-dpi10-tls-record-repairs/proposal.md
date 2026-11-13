# Change: Repair TLS record prelude contracts

Task ID: `RST-1794557076132326`

## Why

The DPI-10 audit found a repeated header correction, unbounded repeat work, and tests that pass without applying the prelude. Existing multi-record parsing does not reach the record emitter for all valid ClientHello layouts.

## What Changes

- Apply adaptive record offsets in the selected payload coordinate.
- Reject excessive repeat work before a partial prelude can be used.
- Require applied results in adaptive tests.
- Process complete multi-record ClientHello inputs and preserve their tail.
- Use span-aware ECH metadata for activation.
- No persisted or configuration schema changes. Excessive repeat requests use the existing planner-error fallback.

## Capabilities

### New Capabilities

- `tls-record-prelude`: byte-preserving record transformation and bounded execution.

### Modified Capabilities

- None. General TLS inspection contracts remain unchanged.

## Impact

- ripdpi-desync, ripdpi-desync-runtime, and ripdpi-proxy-runtime-desync-adapter.
- No new dependencies, public IR change, Android resource change, or raw/root operation.
