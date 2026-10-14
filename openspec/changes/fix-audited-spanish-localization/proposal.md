# Change: Fix audited Spanish localization

Task ID: `UIX-1791875091386561`

## Why

Spanish users receive English warnings, wrong privacy terms, and inconsistent action names despite complete resource keys. Diagnostic results and numbers also bypass the selected language.

## What Changes

- Correct the 125 active and four unused resource findings from the audit at `2f6803083d74880f3c47e209aa83dfe9d6953e33`.
- Localize known DoQ, DoH bootstrap, BYOH, SNI, and network snapshot labels.
- Format visible diagnostic and notification numbers in the selected app locale.
- Preserve complete resource keys and format arguments in all ten locales.

## Capabilities

### New Capabilities

- `spanish-localization`: Accurate Spanish text and localized diagnostic presentation.

### Modified Capabilities

- None.

## Impact

- App and service presentation, locale resources, regression tests, and translation manifest.
- No breaking change, dependency, network behavior, wire contract, or storage schema change.
