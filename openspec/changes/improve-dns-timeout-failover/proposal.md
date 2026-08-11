# Change: Bound encrypted DNS timeout failover

Task ID: `SVC-1786488973639528`

## Why

When encrypted DNS is unavailable during VPN bootstrap, a timeout currently
consumes two complete queries before failover. The reported diagnostic session
repeated this delay across five encrypted resolver paths, producing ten timeout
waits before exhausting the candidate chain even though the preferred resolver
later recovered. Startup DNS recovery must move past an unresponsive path after
the first bootstrap timeout without treating a transient timeout as permanent
network blocking.

## What Changes

- Treat a timeout on an encrypted resolver's bootstrap queries as an eager
  failover signal.
- Keep timeout-only failures session-local instead of persisting the resolver
  path as blocked for the network.
- Preserve the two-failure threshold for post-bootstrap timeout failures and
  preserve strict encrypted-only DNS with no plaintext fallback.
- No breaking changes.

## Capabilities

### New Capabilities

- `encrypted-dns-timeout-failover`: Bounds bootstrap timeout retries while
  retaining recovered encrypted resolver paths for future sessions.

### Modified Capabilities

- None.

## Impact

- `core/service` encrypted DNS failover state and unit tests.
- Network-scoped blocked-path persistence semantics for timeout-only failures.
- No wire, protobuf, JNI, dependency, or user-data migration changes.
