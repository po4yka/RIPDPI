# Select the latest completed diagnostics session for export

Task ID: `DGN-1795345317475114`

## Why

A running scan can have a staged report. A completed scan can have saved measurements without its full report.

## What Changes

Select the most recent completion for an automatic archive. Keep its saved measurements and record when the full report is unavailable.

## Capabilities

### New Capabilities

- `latest-completed-diagnostics-export` — Choose a completed session for automatic export.

### Modified Capabilities

- None.

## Impact

Local diagnostics archive selection and its Room query.
