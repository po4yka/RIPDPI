# Change: Correct DPI-23 QUIC packet generation

Task ID: `ENG-1794637912833130`

## Why

A valid local profile can request unbounded QUIC decoy generation. Repeated gap series reuse a fixed Initial key/nonce context. An unrelated decoy response may also mark the original flow successful. These findings need bounded, testable behavior.

## What Changes

- Reject oversized packet-number-gap counts and bound direct model planning.
- Give each gap series one fresh synthetic context. Preserve packet numbers 2, 4, 6 and the original datagram.
- Reproduce and correct decoy-only response attribution while preserving transparent UDP delivery.
- BREAKING: saved chains with more than 64 requested gap decoys are rejected instead of performing unbounded work. No schema changes are required.

## Capabilities

### New Capabilities

- `quic-gap-generation-safety`: bounded gap generation, fresh synthetic contexts and correct response evidence.

### Modified Capabilities

- None.

## Impact

- Kotlin data-model validation; Rust proxy admission, UDP planner and runtime response accounting.
- No new dependencies, persistent schema, JNI signature, locale or golden fixture changes.
- Host and loopback acceptance only. Device, external network, hosted CI, release and deployment evidence are separate.
