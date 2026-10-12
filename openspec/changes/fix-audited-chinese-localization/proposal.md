# Change: Fix audited Chinese localization

Task ID: `UIX-1791818839787311`

## Why

Chinese users receive English warnings and actions, incorrect network terms, and untranslated tool results despite complete resource keys.

## What Changes

- Correct the 116 active resource findings from the 2026-10-12 audit.
- Translate known diagnostic results, connection states, tool captions, and saved-network dates at the presentation boundary.
- Correct the dormant redaction text and Quick Connect label consistency.
- Keep all ten locale resource sets complete. Preserve protocol names and custom values.

## Capabilities

### New Capabilities

- `chinese-localization`: Accurate Chinese user text and localized diagnostic presentation.

### Modified Capabilities

- None.

## Impact

- App resources, Kotlin presentation, tests, and translation manifest.
- No breaking change, new dependency, network behavior, wire or storage schema change.
