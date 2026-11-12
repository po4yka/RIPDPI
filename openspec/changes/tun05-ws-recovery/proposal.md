# Change: Recover established WireGuard WebSocket sessions

Task ID: `SVC-1794477234440384`

## Why

An established WS carrier currently stops the VPN after a transient disconnect, although the native receive loop promises supervisor recovery.

## What Changes

- Retry established WS sessions with a fresh native runtime and bounded backoff.
- Keep the installed VPN and proxy path while recovery runs.
- Cancel recovery on Stop and ignore stale callbacks.
- Preserve UDP fail-stop behavior. No breaking schema change.

## Capabilities

### New Capabilities

- `awg-ws-recovery`: bounded recovery of an established WS carrier.

### Modified Capabilities

- None.

## Impact

- `:core:service` AWG supervisor and regression tests.
- No dependency, JNI, storage, locale, or wire schema changes.
