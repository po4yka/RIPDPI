# Change: Exclude privileged Android features from Google Play

Task ID: `AND-1795444347241489`

## Why

The Play, F-Droid, and GitHub variants currently share privileged Kotlin, JNI, and assets. The Play variant needs a base runtime with only the supported app capabilities.

## What Changes

- Compile and package privileged features for F-Droid and GitHub.
- Apply Play capability rules to UI, input, stored state, and diagnostics.
- Verify the packaged Play variant before publishing.

## Capabilities

### New Capabilities

- `android/distribution-privileges` defines the build and execution boundary.

### Modified Capabilities

None.

## Impact

Android build variants, VPN and proxy runtime, strategy validation, diagnostics, translations, and release verification.
