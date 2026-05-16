---
title: Introduce ProtocolVersion enum and version-mismatch probe diagnostic
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: [add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Introduce ProtocolVersion enum and version-mismatch probe diagnostic #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `introduce-protocol-version-enum-and-version-probe-diagnostic`
- **Verify:** `cargo test -p ripdpi-vless -p ripdpi-tuic -p ripdpi-ws-tunnel -p ripdpi-diagnostics-protocols`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `native/rust/crates/ripdpi-tuic/**`, `native/rust/crates/ripdpi-ws-tunnel/**`, `native/rust/crates/ripdpi-diagnostics-protocols/**`, `native/rust/crates/ripdpi-failure-classifier/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** `add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols`
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Replace hard-coded protocol-version magic numbers with a typed
`ProtocolVersion` enum and add a diagnostic probe that distinguishes
"server speaks wrong wire version" from "blocked / wrong key / network
failure" in user-facing diagnostics.

## Context

Current state:

- `native/rust/crates/ripdpi-vless/src/wire.rs:35` writes `buf.push(0x01)`
  as the VLESS version byte with no constant or enum.
- `native/rust/crates/ripdpi-tuic/src/protocol.rs:11` pins
  `pub(crate) const TUIC_VERSION: u8 = 0x05`.
- `native/rust/crates/ripdpi-ws-tunnel/src/mtproto.rs:8-17` defines
  several `ENCRYPTED_PREFIX_*` and `ALLOWED_PROTOCOL_TAGS` constants
  with no shared abstraction.

Failure mode today: when an upstream server bumps the wire version,
clients fail at handshake-read time and the failure is reported as a
generic protocol error. The user cannot tell whether they are blocked,
misconfigured, or running an outdated client.

## Acceptance criteria

- [x] (2026-05-15) Each protocol crate exposes a typed enum.
    - `ripdpi-vless::wire::ProtocolVersion` (`V1`) with
      `wire_byte` + `from_wire_byte` + `SUPPORTED`.
    - `ripdpi-ws-tunnel::mtproto::MtprotoTransportFamily`
      (`PaddedIntermediate`, `Intermediate`, `Abridged`) with
      `tag_bytes` + `from_tag_bytes` + `SUPPORTED`; legacy
      `ALLOWED_PROTOCOL_TAGS` const derived from the enum.
    - `ripdpi-tuic::protocol::ProtocolVersion` (`V5`) with
      `wire_byte` + `from_wire_byte` + `SUPPORTED`; `TUIC_VERSION`
      const derived from the enum.
- [x] (partial, 2026-05-15) Wire encode/decode paths use the enum
    (no bare `0x01` / `0x05` literals in encode or decode arms) —
    **in `ripdpi-vless` only**. `encode_request` writes
    `ProtocolVersion::V1.wire_byte()`; `parse_request_header` uses
    `ProtocolVersion::from_wire_byte().is_none()` for rejection.
- [ ] A new `ripdpi-diagnostics-protocols` probe attempts a low-cost
    handshake and classifies failures into `Reachable`,
    `VersionMismatch { offered, server_signaled }`, `AuthFailure`,
    `BlockedOrDropped`, and `Unknown`.
- [ ] `ripdpi-failure-classifier` maps `VersionMismatch` to a distinct
    user-visible class with a remediation hint ("server upgraded; update
    client or change profile").
- [ ] Unit tests cover encode/decode of every enum variant plus the
    classifier outputs for synthetic failure traces.

## Definition of done

- No remaining `0x01` / `0x05` literal in the version slot of any wire
  encoder; `cargo clippy --workspace -- -D warnings` clean.
- Probe is callable from the diagnostics CLI and from the Android
  diagnostic surface (no UI required for this task).

## Risks / open questions

- Hysteria 2 and TUIC do not always echo the server-side version on
  rejection. For those protocols, `VersionMismatch` may have to be
  inferred from changelog-known bump points rather than from the wire.
- Distinguishing version-mismatch from active blocking on QUIC paths is
  inherently noisier; classifier confidence should be surfaced as
  `Likely(...)` rather than absolute.

## Links

- [[add-spec-version-pinning-and-upstream-watch-ci-for-vendored-protocols]]
- [[Epic - Control-plane hardening]]
