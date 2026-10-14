# Change: Improve Material 3 readability and navigation

Task ID: `UIX-1791793531264679`

## Why

Small primary text and icons make the app hard to scan. Settings values consume too much row width. At large font sizes, diagnostics navigation uses a vertical selector above the results.

## What Changes

- Increase primary text and icon sizes while preserving small metadata roles.
- Keep settings labels readable and place long values below the label.
- Use horizontal Material 3 tabs for diagnostics at all font sizes.
- Update the design contract and inspect fresh light, dark, and large-font renders.
- Extend the same readability rules to compact actions, feedback, DNS and import cards, choice groups, bottom navigation, and data-screen headers.
- Repair detached throughput chip/stat surfaces and recovery action labels found in current verified screenshots.
- No breaking data, service, network, or storage changes.

## Capabilities

### New Capabilities

- `ui/readable-material3-controls`: readable shared typography, settings rows, and diagnostics navigation.

### Modified Capabilities

- None.

## Impact

- Android app UI, UI tests, and design documentation.
- No new production dependencies or locale keys.
- Existing screenshot fixtures will differ; blessing requires separate authorization.
