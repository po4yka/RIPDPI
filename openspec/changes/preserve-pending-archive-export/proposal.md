# Change: Preserve pending diagnostics archive export

Task ID: `AND-1790432494546766`

## Why

Android can recreate the Activity while the user selects a destination for a diagnostics archive. The current Activity-scoped pending export disappears, so the returned document URI is ignored and the created document remains empty.

## What Changes

- Complete the original archive export after the document picker returns to a recreated Activity.
- Clear the pending export when the picker is cancelled.

## Capabilities

### New Capabilities

- `diagnostics-archive-picker`: preserve a pending archive export through Activity recreation.

### Modified Capabilities

- None.

## Impact

- Android `:app` Activity host and its unit tests. No external service, native crate, or wire contract changes.
