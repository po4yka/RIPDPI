# Change: Fix Tor relay audit defects

Task ID: `RLY-1794300120922878`

## Why

The Tor proxy path leaks DNS queries, loses names, and applies a short deadline. Bootstrap retries can report false readiness. Quiet PT processes can survive shutdown. Profile paths collide and draft validation accepts empty bridges.

## What Changes

- Preserve Tor names and use the Tor dial budget.
- Wait for real bootstrap readiness and stop quiet PT children.
- Isolate profile state and reject invalid Tor bridge drafts.
- No JNI or protobuf schema change is planned. Existing ambiguous state requires conservative migration.

## Capabilities

### New Capabilities

- `tor-relay-correctness`: Tor routing, state and lifecycle safety.

### Modified Capabilities

- None.

## Impact

- Rust proxy, Arti wrapper and pinned vendor sources; Kotlin relay configuration and editor validation.
