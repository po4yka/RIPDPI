# Change: Improve root mode in extended Android builds

Task ID: `AND-1795456111842469`

## Why

A saved grant does not show whether the helper can serve the current connection. Users need a clear recovery action when access is lost or the helper fails.

## What Changes

- Show the saved opt-in, the latest access result, and the current helper state.
- Start the helper on demand and return to the ordinary connection path when possible.
- Restrict privileged backend startup to verified shipped assets.

## Capabilities

### New Capabilities

- `extended-root-runtime`: Root access, lifecycle, and recovery in F-Droid and GitHub builds.

### Modified Capabilities

- None.

## Impact

- Extended app and service source sets, helper binary, and Android acceptance tests.
- Google Play stays free of privileged code and resources. PCAP keeps its own consent and active VPN requirements.
