# Change: Repair WebSocket and raw Upgrade contracts

Task ID: `TRN-1794383445146007`

## Why

The TRN-09 audit reproduced six transport defects. A valid WebSocket response can fail, an unrequested extension can pass, relay failures can report success, and peer Close replies can remain queued. The internal raw Upgrade API accepts invalid HTTP syntax and unconfirmed protocol selection.

Follow-up source and specification review found that the verifier accepts repeated singleton Accept and Protocol response fields when their first value is valid. RFC 6455 forbids these repeated fields. A raw-wire regression will verify this additional defect before repair.

The same review found that the WS verifier checks only the first Upgrade field. An additional unoffered protocol can pass. Repeated fields form a combined value, so this response must fail the existing single WebSocket value check. The raw Upgrade parser already rejects extra selected protocols.

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
