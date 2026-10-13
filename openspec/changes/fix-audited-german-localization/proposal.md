# Change: Correct German localization and diagnostic presentation

Task ID: `UIX-1791912007411833`

## Why

German users see English controls and privacy warnings, misleading technical terms, and non-German measurements. Telegram also labels native byte rates as bit rates without conversion.

## What Changes

- Correct the 107 active and five unused German audit entries.
- Localize known Telegram badges and readable share-summary captions.
- Format measurements with the selected app locale and correct throughput units.
- Preserve raw evidence, all locale keys, protocols, and stored values. No breaking wire or storage changes.

## Capabilities

### New Capabilities

- `german-localization`: Correct German resources and localized diagnostic presentation.

### Modified Capabilities

- None.

## Impact

- App resource XML, Kotlin presentation, core/diagnostics summary-heading parameters, unit/resource tests and localization provenance. Existing CI discovers the new regression suite.
- All ten locales receive new captions together. Service, native producers, schema and dependencies do not change.
