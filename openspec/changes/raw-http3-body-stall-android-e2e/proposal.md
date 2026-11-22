# Exercise the RAW HTTP/3 body stall in Android history

Task ID: `DGN-1795367419931764`

## Why

Android instrumentation can verify that a completed TCP transfer and a stalled HTTP/3 body reach the saved report with their distinct stages.

## What Changes

Extend the local HTTPS fixture with a controlled HTTP/3 response on the same port. Add the manual RAW comparison to the Android test runner.

## Capabilities

### New Capabilities

- raw-http3-body-stall-android-e2e

### Modified Capabilities

- None.

## Impact

Local network fixture, Android instrumentation, and the opt-in network E2E runner.
