# Change: Repair DPI-25 QUIC Initial contracts

Task ID: `ENG-1794638006879351`

## Why

DPI-25 emits TCP-shaped TLS messages without QUIC transport parameters, repeats connection state, loses the configured UDP TTL, accepts unbounded counts, and can exceed its size ceiling. Local independent packet checks reproduced these defects.

## What Changes

- Emit protocol-correct synthetic QUIC v1/v2 ClientHello messages with h3 and matching source CID parameters.
- Give each alternating profile a separate fresh synthetic connection.
- Preserve and apply the configured UDP fake TTL.
- Reject DPI-25 counts above 32 per step or chain and protect the direct planner.
- Keep decoy datagrams within a conservative encoded size budget.
- BREAKING: saved configurations with more than 32 DPI-25 decoys are rejected with a clear validation error.

## Capabilities

### New Capabilities

- `quic-multi-initial-safety`: valid synthetic packet state and bounded local work.

### Modified Capabilities

- None. This adds an explicit contract for the existing strategy.

## Impact

- Kotlin data model validation; native config, packet, desync and runtime adapter crates.
- New seed API and count constant; generated API snapshots.
- No schema, dependency, locale, JNI signature or stored field changes. No exact browser-version fingerprint claim.
