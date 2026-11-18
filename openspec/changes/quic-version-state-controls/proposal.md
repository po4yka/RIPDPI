# Change: Isolate QUIC version diagnostics from residual flow state

Task ID: `DGN-1795014101783145`

## Why

Existing DIA-07 explicitly excludes QUIC v2 and provides Initial-response evidence, not application reachability; existing version-negotiation decoys are not a valid QUIC v2 comparator. A controlled version matrix and explicit flow-state isolation complement these owners.

## What Changes

- Add the bounded diagnostic behavior specified below, manually initiated and offline-first.
- Preserve current default behavior and distinguish observations from cause hypotheses.

## Capabilities

### New Capabilities

- `quic-version-state-controls`: scoped diagnostic comparison evidence.

### Modified Capabilities

- None; integrate through existing diagnostic owners without weakening their guarantees.

## Impact

Existing native diagnostics runner/probes/contracts/classification, core diagnostics/history/export and manual diagnostic UI. Future schema changes require synchronized Rust/Kotlin updates. No new required backend.

## Sources

https://petsymposium.org/foci/2026/foci-2026-0010.php

The paper reports version-sensitive and residual flow filtering in March-April 2026 at three vantage points. Its observed timeout is a historical measurement, not a permanent provider guarantee. See also https://github.com/UPB-SysSec/RussiaQuicResults .
