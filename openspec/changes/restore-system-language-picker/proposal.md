# Change: Restore system language in the picker

Task ID: `UIX-1790432109043744`

## Why

After a user selects an app language, the language picker offers no way to
return to the device language. The override persists until a platform-level
reset outside the app.

## What Changes

- Offer a device-language choice in the app language picker.
- Show that choice as selected when the app follows the device language.
- Keep explicit language choices working as before.
- No breaking changes.

## Capabilities

### New Capabilities

- `app-language-selection`: Selection and restoration of the device language.

### Modified Capabilities

- None.

## Impact

- `:app` language picker, its direct test, and all ten app locale resource sets.
- No service, native, persistence schema, or network contract changes.
