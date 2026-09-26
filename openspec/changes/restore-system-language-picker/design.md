## Context

`LanguagePickerSheet` reads `AppCompatDelegate.getApplicationLocales()` and
shows only tags from `LocalesConfig`. An empty app locale list means the app
follows the device, but the picker has no row for that state.

## Goals / Non-Goals

- Goal: Make the device-language state visible and selectable in the picker.
- Non-goal: Change the set of supported explicit languages.

## Decisions

- Add one radio row above the explicit languages. An empty app locale list
  selects this row. Choosing it calls `setApplicationLocales` with an empty
  `LocaleListCompat`.
- Keep explicit language rows selected only when a nonempty override matches
  their tag. This prevents the effective device language from looking like an
  explicit choice.
- Use the existing `LanguageRow` presentation and a localized string resource.

## Contracts and ownership

- Affected module: `:app`. No Rust crates or public, wire, storage, or service
  contracts change.
- The UI audit agent owns the picker, direct test, and all ten app locale
  resource sets as a serialized file lane.

## Risks / Trade-offs

- Locale changes recreate the activity. Read the override when the picker
  opens so the selected row reflects the current delegate state.
- Translation parity can drift. Run app and service lint after all ten strings
  are present.

## Migration Plan

No migration is required. Existing overrides remain until the user chooses
the new option. Reverting the UI change restores the old picker behavior.
Validate with a focused picker test, app unit and source checks, and
`:app:lintGithubFullDebug :core:service:lintDebug`.
