## Context

The audit identifies six defects in existing transport paths. The raw Upgrade and generic WS adapters are internal APIs. Telegram and WG carriers have real runtime callers. Use the current pinned dependency source and independent byte vectors.

## Goals / Non-Goals

- Goal: correct validation, terminal error propagation, Close replies, and confirmed privacy/resource causes.
- Non-goal: new relay kinds, protocol admission, early-data session drivers, or public peer deployment.

## Decisions

- Correct the shared pinned WS verifier with a narrow vendored patch if no existing repository seam can preserve all handshake checks. Do not add a new production dependency or replace TLS.
- Keep F1 through F6 in separate commits. Regression tests assert peer bytes and observable errors.
- Preserve retryable read polling and normal Close. Do not replay a partially sent stream after relay failure.
- Validate raw HTTP with bounded existing HTTP types and preserve the existing default API where possible. Custom Upgrade protocol remains explicit.
- The close owner covers the generic async adapter, split WG driver, and its receive size limit. Use bounded, cancellation-safe state that survives a pending poll.
- Hypotheses need source or local proof before behavioral changes. Unspecified service shutdown SLA and external compatibility remain evidence gaps rather than invented guarantees.

## Contracts and ownership

- Scope and exact writer paths are in the linked portfolio task. Root owns Cargo.toml, Cargo.lock, vendor source, snapshots and all planning records.
- Affected Rust crates: ripdpi-ws-tunnel, ripdpi-wireguard-ws, ripdpi-warp-core and their shared WebSocket dependency. Kotlin modules and JNI/protobuf/storage contracts do not change.
- Read-only pr-reviewer and async-cancel-safety review precede integration. No unsafe or JNI changes are planned.

## Risks / Trade-offs

- A dependency patch adds a maintenance obligation; record its pinned source and exact changes.
- Close flush must not block forever or lose state when cancelled. Tests use a stalled peer and peer Close frames.
- Tighter WG limits must admit every supported datagram. Use the existing owner contract rather than a guessed MTU.

## Migration Plan

No persisted migration. Roll back the individual defect commit if a regression is confirmed. Run owning tests with --locked, format and affected clippy, just ci-gates, architecture health, cargo metadata --locked, and read-only reviews on the combined tree. Public peer, physical device, release artifact and production deployment evidence are outside this source repair. The user authorized main integration and origin/main push after validation.
