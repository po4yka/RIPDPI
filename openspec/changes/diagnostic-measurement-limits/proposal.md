# Change: Expose diagnostic measurement limits

Task ID: `DGN-1791484787190798`

## Why

Reports drop control validation and do not state what the existing probes measure. Users can mistake an observed failure for proof of provider interference.

## What Changes

- Show matching control state and measurement limits in diagnosis cards and shared text.
- Label interface MTU and state that path MTU is not measured.
- Preserve unknown network facts in summaries and exports.
- No breaking contract or new network probe.

## Capabilities

### New Capabilities

- `diagnostic-measurement-limits`: Visible measurement and control boundaries.

### Modified Capabilities

- None.

## Impact

- App presentation, all ten locales, and core diagnostics summaries. No native schema or dependency change.
