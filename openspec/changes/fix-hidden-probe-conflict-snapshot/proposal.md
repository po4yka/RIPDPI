# Change: Preserve manual scan options across hidden probe conflicts

Task ID: `DGN-1790432046570971`

## Why

Conflict resolution currently restarts a manual scan with default options. It loses the original deadline, candidate limit, target choices, owner, and raw-path resume policy.

## What Changes

- Store the original manual start options in the pending conflict request.
- Apply them when WAIT or CANCEL_AND_RUN starts the scan.
- No schema, JNI, or wire contract change.

## Capabilities

### Modified Capabilities

- `diagnostics/manual-scan-admission`: A resolved conflict starts the originally requested scan.

## Impact

- `:core:diagnostics` scan controller and tests only.
