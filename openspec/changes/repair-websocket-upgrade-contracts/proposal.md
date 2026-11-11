# Change: Repair WebSocket and raw Upgrade contracts

Task ID: `TRN-1794383445146007`

## Why

The TRN-09 audit reproduced six transport defects. A valid WebSocket response can fail, an unrequested extension can pass, relay failures can report success, and peer Close replies can remain queued. The internal raw Upgrade API accepts invalid HTTP syntax and unconfirmed protocol selection.

## What Changes

- Repair each confirmed defect in a separate commit with independent wire regression tests.
- Check the recorded credential logging and resource risks; fix confirmed in-scope causes.
- Keep existing TLS trust, backend admission, and payload formats.
- No persisted or JNI schema changes. Invalid HTTP inputs are rejected earlier.

## Capabilities

### New Capabilities

- `websocket-upgrade-contracts`: validation, error propagation, and termination of existing WS and raw Upgrade paths.

### Modified Capabilities

- None.

## Impact

- Rust WS tunnel, WireGuard WS, carrier lifecycle, and pinned WebSocket verifier.
- Cargo patch/lock and local regression tests; no backend activation, UI, or production service.
