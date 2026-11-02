# Change: Persist and show the app lock reset notice

Task ID: `DAT-1793534707148297`

## Why

When the PIN key is lost, the app wipes the PIN and turns app lock off. The Settings list shows a notice, but the notice is in memory only. After process death, nothing tells the user why app lock is off. The user can believe that app lock still protects the app.

## What Changes

- Add a persisted "app lock was reset" field to `AppSettings`, with a serializer default.
- Set the flag in the same update that wipes the PIN.
- Show the notice on the Settings list while the flag is set.
- Clear the flag when the user acknowledges the notice or sets a new PIN.
- Decide and document backup and restore handling of the flag.
- Document the field and add a migration test.

## Capabilities

### New Capabilities

- `app-lock-reset-notice`: Persist and show the app lock reset notice

### Modified Capabilities

- None.

## Impact

- Portfolio area: `data`.
- `app_settings.proto`, `AppSettingsSerializer`, and the settings repository.
- Settings notice code and the PIN reset path in `:app`.
- Settings backup and restore.
- `docs/architecture/CONFIG_CONTRACTS.md`.
