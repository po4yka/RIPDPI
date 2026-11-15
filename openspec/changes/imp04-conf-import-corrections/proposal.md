# Change: Correct WireGuard and AmneziaWG conf import

Task ID: `DAT-1794728756596106`

## Why

The importer can broaden or lose routes and commit an invalid profile. Entry points disagree on the format, and the preview does not identify lost settings.

## What Changes

- Preserve lists, explicit route policy and the effective keepalive.
- Reject invalid requests before save and reset the carrier on conf import.
- Share one grammar and size limit, keep errors free of input text, and identify ignored settings.
- Recognize AWG syntax and define empty optional AWG 3.x fields.

## Capabilities

### New Capabilities

- wg-conf-import-safety: Safe import, preview and activation of standalone profiles.

### Modified Capabilities

- None.

## Impact

App import, runtime-state model and parser, locale resources, and regression tests.
