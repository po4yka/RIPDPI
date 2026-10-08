# Change: Keep unavailable observations unknown

Task ID: `DGN-1791484646270191`

## Why

Missing capabilities and link properties currently become false validation or system DNS values. Missing roaming currently becomes false in native metadata.

## What Changes

- Record whether capabilities and link properties were observed.
- Display unknown for unavailable validation and Private DNS values, including ambiguous legacy defaults.
- Omit native cellular details when roaming cannot be established.

## Capabilities

### New Capabilities

- `network-metadata-availability`: Explicit missing network observations.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostics, service and app modules. Optional backward-compatible archive metadata; no native schema change.
