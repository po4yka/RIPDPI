# Change: Preserve share summary warnings behind information events

Task ID: `DGN-1790431550325964`

## Why

A summary now reads 50 recent native events and then discards information events. Fifty newer information events can hide an older warning, leaving the summary without useful failure evidence.

## What Changes

- The share summary selects warning and error events before it limits the result to 50.
- Selected-session and live summaries keep their existing scope and order.
- No breaking data schema change is required.

## Capabilities

### New Capabilities

- `diagnostics/share-summary-warnings`: Bounded, warning-only native event selection for share summaries.

### Modified Capabilities

- None.

## Impact

- `:core:diagnostics` share summary behavior and tests.
- `:core:diagnostics-data` native event query contract, Room DAO, adapter, and tests.
