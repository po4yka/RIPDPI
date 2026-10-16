# Change: Clarify settings and Simple actions with compact navigation

Task ID: `UIX-1792145277214284`

## Why

Stage two simplified connection and result screens. Settings still repeats
Manage instead of useful values. Simple can save diagnostic changes before
completion without making that consequence clear. Neutral measurements show
a warning symbol, and large-font navigation spends height on decorative icons.
The translation-export manifest also omits the new stage-two string keys.

## What Changes

- Group settings by user task and show actual values where available.
- Explain Simple diagnostic effects, show applied settings and expose existing
  backup/restore with clear limits and no factory-reset action.
- Use neutral information treatment for unmeasured or historical values.
- Keep all navigation labels visible with compact large-font fallback rows.
- Regenerate the translation manifest with its owning tool and check it again
  after every resource change. No breaking stored or network contract changes.

## Capabilities

### New Capabilities

- `ui/stage-three-clarity`: settings, Simple effects and compact accessible navigation.

### Modified Capabilities

- None. Existing service, diagnostics apply, backup payload and persistence
  contracts remain unchanged.

## Impact

- App presentation, locale resources, UI tests and design documentation.
- Generated translation key manifest. No dependency, Rust, JNI, protobuf,
  backend, service lifecycle or database changes.
