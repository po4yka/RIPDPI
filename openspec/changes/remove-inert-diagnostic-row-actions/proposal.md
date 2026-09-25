# Change: Remove inert diagnostic row actions

Task ID: `DGN-1790339654179112`

## Why

Several diagnostic rows look and sound clickable but their handlers do nothing, misleading touch and screen-reader users.

## What Changes

- Present rows without a detail action as non-interactive information.
- Preserve click actions where a detail view exists.
- No breaking contract or schema change.

## Capabilities

### New Capabilities

- `diagnostic-row-actions`: diagnostic row semantics follow their actual available action.

### Modified Capabilities

- None.

## Impact

- `:app` diagnostic Compose UI and tests only. No storage, native, or network change.
