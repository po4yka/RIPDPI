# Change: Require evidence for Home DNS interference claims

Task ID: `DGN-1791481936635788`

## Why

Disjoint CDN answers and resolver failures do not establish provider interference.

## What Changes

- Preserve uncertain DNS results without a poisoning or proxy claim.

## Capabilities

### New Capabilities

- `home-dns-evidence`: conservative Home DNS interpretation.

### Modified Capabilities

- None.

## Impact

- App augmentation, core diagnostics summaries, core detection mapping. No schema change.
