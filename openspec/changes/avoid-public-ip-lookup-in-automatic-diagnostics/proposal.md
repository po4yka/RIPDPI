# Change: Avoid external public-IP lookup in automatic diagnostics

Task ID: `DGN-1790434142197991`

## Why

Automatic background scans capture network snapshots before and after execution with `includePublicIp=true`. This contacts third-party public-IP resolvers without a user-visible scan action.

## What Changes

- Automatic background scans omit external public-IP lookup in both snapshots.
- User-initiated scans keep their current lookup behavior. A DNS-corrected re-probe inherits the original scan's permission for its post-scan snapshot.
- A complete user-controlled public-IP opt-in remains a separate product decision.

## Capabilities

### Modified Capabilities

- `diagnostics/automatic-scan-privacy`: Limit background snapshot network access.

## Impact

- `:core:diagnostics` request preparation, finalization, and tests.
