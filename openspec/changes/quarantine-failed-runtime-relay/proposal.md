# Change: Quarantine confirmed failed runtime relays

Task ID: `SVC-1786565057976588`

## Why

The runtime failover probe can confirm that the active relay no longer provides the required TCP or UDP egress, but only sustained XUDP failures enter the network-scoped negative cache. A relay with confirmed TCP failure is therefore selected again by later initial races, causing repeated timeouts and prolonged VPN failure.

## What Changes

- Record every failed active relay egress confirmation against the exact network scope, capability proof, relay kind, and profile.
- Exclude a confirmed failed relay from subsequent candidate selection until the existing bounded cooldown expires.
- Preserve successful-probe recovery, candidate switching, and per-network isolation.

## Capabilities

### New Capabilities

- `runtime-relay-failure-quarantine`: Network-scoped quarantine of relay profiles after an active capability probe confirms runtime egress failure.

### Modified Capabilities

- None.

## Impact

- `app/src/simple`: runtime failover coordination and its JVM regression coverage.
- Existing `SimpleEgressHealthMemory` persistence contract; no schema, JNI, wire, dependency, or locale change.
