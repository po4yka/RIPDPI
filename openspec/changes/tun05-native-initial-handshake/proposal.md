# Change: Bound the initial AmneziaWG handshake

Task ID: `RST-1794477240707368`

## Why

A blocked first WebSocket write can prevent Stop and the peer handshake timeout from completing.

## What Changes

- Stop and the peer startup deadline cover the initial initiation write and authenticated readiness.
- Cancellation ends the tunnel and does not replay a buffered frame.
- No breaking wire, storage, JNI, or configuration change.

## Capabilities

### New Capabilities

- `amneziawg-startup`: bounded, cancellable peer startup.

### Modified Capabilities

- None.

## Impact

- `ripdpi-warp-core` startup and its local regression tests only.
