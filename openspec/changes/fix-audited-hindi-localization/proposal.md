# Change: Correct audited Hindi localization

Task ID: `UIX-1791880509070975`

## Why

Hindi users receive wrong privacy and state meanings, mismatched action names, and English labels despite complete string keys. Some catalog dates retain the previous app language.

## What Changes

- Correct the 63 active and seven unused confirmed resource findings at 17172d4a1fe3bfc02faf6366efad868da2ad3dce.
- Translate descriptive settings options, known diagnostic labels, and built-in host-pack descriptions.
- Format catalog dates in the selected app language after a language change.
- Preserve all ten locales, technical identifiers, and resource arguments. Leave the three disputed DNS terms unchanged.

## Capabilities

### New Capabilities

- `hindi-localization`: Accurate audited Hindi text and localized settings and diagnostic presentation.

### Modified Capabilities

- None.

## Impact

- App presentation, locale resources, translation manifest, and regression tests.
- No breaking change, dependency, storage schema, wire contract, or network behavior change.
