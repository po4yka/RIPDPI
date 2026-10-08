# Change: Keep network metadata in one capture scope

Task ID: `DGN-1791484491975137`

## Why

A public IP lookup can suspend while the active network changes. Metadata captured before and after that lookup must not describe one network.

## What Changes

- Reject metadata when physical or calling-default path continuity cannot be established.
- Check native metadata across fingerprint and platform reads.
- Keep raw network identity out of persistence.

## Capabilities

### New Capabilities

- `network-metadata-scope`: Coherent metadata capture.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostics and service modules. No JNI or native wire changes.
