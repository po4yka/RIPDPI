# Change: Fix SSH relay trust and lifecycle faults

Task ID: `RLY-1794300186609700`

## Why

The RLY-11 audit found gaps in SSH host-key checks, import admission, credential sharing, address fallback, and connection cancellation. These gaps can select unusable profiles or leave connection work waiting after cancellation.

## What Changes

- Apply the configured host-key pin at each key exchange.
- Reject invalid SSH imports before durable writes and require an explicit decision for an unpinned key.
- Preserve a nonempty private-key passphrase without trimming it.
- Reserve discovery time for each resolved address.
- Stop owned SSH work even when a retained channel has unread data.
- Keep one correction commit for each finding. No configuration schema changes are planned.

## Capabilities

### New Capabilities

- `ssh-relay-safety`: SSH trust, profile admission, secret round trips, address fallback, and cancellation.

### Modified Capabilities

- None.

## Impact

- Android profile import and SSH editor integration, credential share mapping, host-key observation, and the native SSH transport.
- No new service or protocol. UDP remains unsupported. Native dependency changes, if needed, remain in the native writer lane.
