# Change: Guard RelayBackend manual match arms against silently-omitted QUIC variants

Task ID: `RLY-1786264762917178`

## Why

The 2026-06-10 Rust API audit noted RelayBackend reached 14 variants (was 12; Mieru and Ssh added). The dispatchpooledbackend! macro was updated correctly. Re-verified 2026-06-11 against native/rust/crates/ripdpi-relay-core/src/backend.rs: of the three manual match self blocks, quicmigrationsnapshot() (backend.rs:85-102) and openudpsession() (backend.rs:122-141) already enumerate all 14 variants with explicit |-joined arms and no catch-all , so adding a variant fails to compile (non-exhaustive…

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `guard-relaybackend-quic-snapshot-exhaustiveness`: Guard RelayBackend manual match arms against silently-omitted QUIC variants

### Modified Capabilities

- None.

## Impact

- Portfolio area: `relay`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
