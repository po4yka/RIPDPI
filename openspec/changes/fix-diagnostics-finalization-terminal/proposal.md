# Change: Publish diagnostics completion after finalization

Task ID: `DGN-1790430631972892`

## Why

An in-path scan can appear completed after the report is stored even if a later post-scan write fails. This hides the incomplete finalization from callers.

## What Changes

- Keep an in-path scan running until all required finalization writes succeed.
- On finalization failure, retain the report and publish a failed session.
- Keep raw-path terminal publication behind the existing durable settlement receipt.
- No breaking contract or schema change.

## Capabilities

### New Capabilities

- `diagnostics/scan-finalization`: Publish an accurate terminal scan status with retained report evidence.

### Modified Capabilities

- None.

## Impact

- `:core:diagnostics` scan finalization and execution coordination; no JNI, wire, storage schema, or dependency change.
