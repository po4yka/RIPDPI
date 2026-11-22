# Exercise manual RAW HTTPS transfers end to end on Android

Task ID: `DGN-1795363995827246`

## Why

The manual comparison has JVM and native loopback coverage. Android instrumentation verifies the real JNI transfer and its saved history.

## What Changes

Add a deterministic local HTTPS fixture and run one RAW comparison on Android.

## Capabilities

### New Capabilities

- raw-https-download-android-e2e

### Modified Capabilities

- None.

## Impact

Android instrumentation, the local network fixture, and opt-in test build setup.
