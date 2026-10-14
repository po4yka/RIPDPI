# Change: Correct audited French localization

Task ID: `UIX-1791958951401122`

## Why

French users see English warnings and controls, incorrect translations and raw diagnostic state codes. The audit confirmed 138 resource keys, three release presentation defects and one debug-only defect against 3458ed9c1541d9a5217e5c4618845ecefd4a97bd.

## What Changes

- Correct the confirmed French text, consent information, recovery actions and option references.
- Show known diagnostic states, missing-network labels and RTT ranges in the selected app language.
- Translate debug-card prose and format durations with the selected app locale.
- Preserve complete resource parity across ten locales and preserve raw data. No breaking changes.

## Capabilities

### New Capabilities

- `french-localization-presentation`: French copy and locale-aware diagnostic display contracts.

### Modified Capabilities

- None.

## Impact

- App resource and presentation layers, resource manifest, tests and localization provenance.
- No new dependencies, storage or wire changes, production access or release artifacts.
