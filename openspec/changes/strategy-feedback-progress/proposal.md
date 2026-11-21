# Confirm relay progress before learning strategy success

Task ID: `ENG-1795281317139456`

## Why

A TCP relay can stall after forwarding its first response. A QUIC Retry can reach the client without advancing the handshake. These signals currently make the route look successful to the learner.

## What Changes

- Keep TCP feedback provisional until the relay finishes without a freeze or transport failure.
- Forward a correlated QUIC Retry and wait for a correlated server Initial before learning success.

## Capabilities

### New Capabilities

- `strategy-feedback-progress` — Confirm relay progress before recording positive strategy evidence.

### Modified Capabilities

- None.

## Impact

TCP relay and QUIC response feedback in the native runtime.
