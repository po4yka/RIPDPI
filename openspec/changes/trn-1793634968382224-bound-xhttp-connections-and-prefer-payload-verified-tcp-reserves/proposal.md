# Change: Bound XHTTP pools and prefer verified TCP reserves

Task ID: `TRN-1793634968382224`

## Why

Imported XHTTP tuning currently disappears before the native pool. A failed UDP carrier can also delay TCP recovery while other UDP reserves are tested first.

## What Changes

- Retain a bounded stream-up XHTTP connection limit from import through storage to the native pool.
- Reject unknown tuning, unsupported modes, and nonzero retirement controls.
- Prefer TCP reserves after a UDP active member fails its payload check. Keep thresholds, cooldown, bounded probes, and encrypted selection when all members fail.
- No deployment, device mutation, or dependency change is included.

## Capabilities

### New Capabilities

- `transports/bounded-xhttp-reserves`: preserve bounded pools and prefer verified TCP recovery.

### Modified Capabilities

- None.

## Impact

- Subscription import, runtime profile storage, relay wire DTOs and Rust VLESS builders.
- Selector payload probing and paired deployment bundle fixtures.
- Existing untuned profiles retain their defaults. Invalid tuning now refuses import instead of disappearing.
