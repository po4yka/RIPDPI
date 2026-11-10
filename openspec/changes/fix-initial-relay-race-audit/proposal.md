# Change: Fix initial relay race audit defects

Task ID: `RLY-1794335293698320`

## Why

A dead provisional candidate can replace a live reserve. Duplicate profile IDs lose runtime ownership. A failed loser stop can still publish success. Cold startup can lose a physical network change. The published specification also describes a product race that is not bound in the application.

## What Changes

- Reject ambiguous candidate plans before startup.
- Select only live candidates and require successful loser cleanup.
- Keep network changes during startup available for the first handover.
- Describe the existing single-profile Simple preflight and preserve explicit inconclusive outcomes.
- Recheck audit hypotheses before changing their behavior.
- No wire, JNI, protobuf, storage, or dependency change is planned. The shared network monitor API gains an additive default cold-observation method. No breaking public API is planned.

## Capabilities

### New Capabilities

- `initial-relay-race-safety`: candidate identity, lifetime, cleanup, and startup network safety.

### Modified Capabilities

- `parallel-active-probe-race-initial-transport-selection`: distinguish the core race from product preflight.
- `relay-health-evidence`: clarify lifecycle isolation and current startup scope.

## Impact

- `:core:service`, the shared data network monitor interface, service unit tests, and OpenSpec documents.
- No application UI text, protocol implementation, or production configuration changes.
