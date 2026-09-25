# Change: Make saved relay profiles selectable and editable

Task ID: `RLY-1790329719797199`

## Why

The VPN summary hides profiles after the third item and only exposes sharing. Editing an ID can overwrite a different saved profile.

## What Changes

- Show every saved VPN relay profile with selection and editing actions.
- Load the chosen profile and its credentials when editing.
- Reject a save that targets a different existing profile ID.
- No breaking contract or schema change.

## Capabilities

### New Capabilities

- `manage-saved-relay-profiles`: Select and edit saved relay profiles safely.

### Modified Capabilities

- None.

## Impact

- Android app UI and config persistence; existing relay stores and settings only.
- No new dependency or wire format.
