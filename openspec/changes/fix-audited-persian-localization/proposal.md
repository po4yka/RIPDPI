# Change: Correct audited Persian localization

Task ID: `UIX-1792059951283875`

## Why

The audit at a6b286e21 confirms 121 active Persian resource defects and six shared presentation groups. A sensitive-settings security warning remains English. Other text changes the meaning of exclusions, restart, credentials, diagnostics and stored catalogs. Generated Approach, Strategy and Home text bypasses locale resources.

## What Changes

- Correct all 121 audited Persian entries while preserving meaning and format arguments.
- Resolve generated Approach and Strategy labels and normal Home results in the selected app language, including retained language changes.
- Keep confidence status colors and protocol grouping stable after localization.
- Add every new key to all ten locales in the same source commit.

## Capabilities

### New Capabilities

- `persian-localization-presentation`: accurate Persian copy and localized generated presentation.

### Modified Capabilities

- None. No breaking contract change.

## Impact

- App presentation, resources, tests, translation manifest and localization evidence.
- Core diagnostics producers are read for template contracts; their stored records and exports remain unchanged.
- No dependency, JNI, protobuf, service lifecycle or network behavior change.
- Main integration and push are authorized. Hosted CI must pass on the published source.
