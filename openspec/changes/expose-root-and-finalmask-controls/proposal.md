# Change: Expose root and valid Finalmask controls

Task ID: `UIX-1790339200447975`

## Why

Root strategies are hidden when root mode is disabled, and the app has no ordinary control to enable it. Finalmask appears for relay kinds that reject it when saving.

## What Changes

- Show the root strategies entry and let the user opt in or out on its screen.
- Show Finalmask controls only for relay configurations that can save them.
- No breaking contract or schema change.

## Capabilities

### New Capabilities

- `root-and-relay-ui-controls`: reachable root setting and valid Finalmask choices.

### Modified Capabilities

- None.

## Impact

- Android app Settings and Mode Editor Compose UI, settings persistence, localized resources, and UI tests. No native or protobuf changes.
