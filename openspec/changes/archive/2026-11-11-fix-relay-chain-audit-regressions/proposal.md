# Change: Fix relay chain audit regressions

Task ID: `RLY-1794335337545384`

## Why

A saved chain can retain old middle hops. Empty middle positions can disappear during startup. Chain shutdown leaves the AnyTLS entry pool open. Partial startup can assign an entry or middle state to the exit, and the editor omits middle hops from trust warnings.

## What Changes

- Preserve the selected ordered route across profile saves and activation.
- Reject incomplete hop positions before startup.
- Drain owned AnyTLS workers during chain shutdown.
- Keep telemetry roles fixed to configured positions and use all hops for trust warnings.
- No wire or storage schema changes. No new supported relay modes.

## Capabilities

### New Capabilities

- `relay-chain-integrity`: route persistence, admission, cleanup, and role reporting for existing chain modes.

### Modified Capabilities

- None.

## Impact

- Android connection editor, profile mapping, service admission, relay-core and relay-tls-transports.
- Existing settings and native JSON fields remain unchanged. No new dependencies or baseline changes.
