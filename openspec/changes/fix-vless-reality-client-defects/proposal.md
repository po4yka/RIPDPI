# Change: Fix audited VLESS REALITY client defects

Task ID: `TRN-1793295071036802`

## Why

Native imports can discard requested authentication and transport settings. XUDP can acknowledge buffered frames, Vision can switch in the middle of a TLS record, and mode or version selection can reject otherwise valid server profiles.

## What Changes

- BREAKING: reject imported options that the native client cannot preserve.
- Honor the upstream method/network alias priority and resolve supported XHTTP auto modes in their security context.
- Complete XUDP writes and flushes within one deadline, preserve TLS record boundaries at Direct, and send the documented REALITY compatibility version.
- Add regression checks for all six fixes and publish one commit per fix.

## Capabilities

### New Capabilities

- `native-vless-reality-compatibility`: Preserve import intent and the native VLESS REALITY stream contracts.

### Modified Capabilities

- None.

## Impact

- Kotlin runtime-state import paths, Rust VLESS/XHTTP clients, and relay builders.
- No new dependency, required backend, JNI/protobuf/storage field, or baseline change.
- Unsupported configurations require correction rather than silent conversion. Existing supported profiles retain their settings.
