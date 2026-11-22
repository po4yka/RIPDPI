# Compare a working direct target through the active VPN

Task ID: `DGN-1795338086230261`

## Why

Home compares failed targets and controls. It can miss a target that works directly and fails on the active path.

## What Changes

Include one working direct domain in the bounded comparison. Use completed HTTP exchanges to choose controls and compare the HTTP outcome on both paths.

## Capabilities

### New Capabilities

- `home-working-path-comparison` — Check one working direct target on the active path.

### Modified Capabilities

- None.

## Impact

Home diagnostics target selection and paired evidence.
