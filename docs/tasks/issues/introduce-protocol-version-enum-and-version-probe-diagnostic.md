---
title: Introduce ProtocolVersion enum and version-mismatch probe diagnostic
type: task
status: done
area: rust-native
priority: medium
owner: unassigned
parent: epic-control-plane-hardening
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-29
---

- [x] #task Introduce ProtocolVersion enum and version-mismatch probe diagnostic #repo/RIPDPI #area/rust-native #status/done 🔼

## Summary

Replace hard-coded protocol-version magic numbers with a typed `ProtocolVersion` enum and add a diagnostic probe that distinguishes "server speaks wrong wire version" from "blocked / wrong key / network failure" in user-facing diagnostics.

## Context

Current state:

- `native/rust/crates/ripdpi-vless/src/wire.rs:35` writes `buf.push(0x01)` as the VLESS version byte with no constant or enum.
- `native/rust/crates/ripdpi-tuic/src/protocol.rs:11` pins `pub(crate) const TUIC_VERSION: u8 = 0x05`.
- `native/rust/crates/ripdpi-ws-tunnel/src/mtproto.rs:8-17` defines several `ENCRYPTED_PREFIX_*` and `ALLOWED_PROTOCOL_TAGS` constants with no shared abstraction.

Failure mode today: when an upstream server bumps the wire version, clients fail at handshake-read time and the failure is reported as a generic protocol error. The user cannot tell whether they are blocked, misconfigured, or running an outdated client.

## Acceptance criteria

- [x] (2026-05-15) Each protocol crate exposes a typed enum. - `ripdpi-vless::wire::ProtocolVersion` (`V1`) with `wire_byte` + `from_wire_byte` + `SUPPORTED`. - `ripdpi-ws-tunnel::mtproto::MtprotoTransportFamily` (`PaddedIntermediate`, `Intermediate`, `Abridged`) with `tag_bytes` + `from_tag_bytes` + `SUPPORTED`; legacy `ALLOWED_PROTOCOL_TAGS` const derived from the enum. - `ripdpi-tuic::protocol::ProtocolVersion` (`V5`) with `wire_byte` + `from_wire_byte` + `SUPPORTED`; `TUIC_VERSION` const derived from the enum.
- [x] (2026-05-29) Wire encode/decode paths use the enum across **all three** protocol crates (no bare `0x01` / `0x05` literals in encode or decode arms). `ripdpi-vless`: `encode_request` writes `ProtocolVersion::V1.wire_byte()`, `parse_request_header` uses `ProtocolVersion::from_wire_byte().is_none()`. `ripdpi-tuic`: encode/decode (`protocol.rs:226/238/267`) use the `TUIC_VERSION` const, which is itself `ProtocolVersion::V5.wire_byte()`; the only `0x05` literals remaining are the enum definition and tests. `ripdpi-ws-tunnel::mtproto`: tags come from `MtprotoTransportFamily::tag_bytes()` and the legacy `ALLOWED_PROTOCOL_TAGS` array is derived from `MtprotoTransportFamily::SUPPORTED`.
- [x] (2026-05-29) A new `ripdpi-diagnostics-protocols::version_probe` classifier maps observed handshake bytes into `Reachable`, `VersionMismatch { offered, server_signaled }`, `AuthFailure`, `BlockedOrDropped`, and `Unknown` (`classify_probe_observation` over `ProbeProtocol::{TuicV5, ShadowTlsV3, VlessRealityV1}`). The pure byte-classifier is kept separate from the network drive so it is fully unit-testable; an empty observation maps to `BlockedOrDropped`.
- [x] (2026-05-29) `ripdpi-failure-classifier` exposes distinct user-visible classes `TuicVersionUnsupported` and `ShadowTlsVersionMismatch`, each with a doc-comment remediation rationale ("recommend upgrading the server rather than retrying"), and `ripdpi-diagnostics-contracts::outcome_policy` buckets `"tuic_version_unsupported"` as `Failed` so the surfacing is wired end to end. (REALITY v1 has no alternate wire version to mismatch against today, so the probe returns `Unknown` there by design.)
- [x] (2026-05-29) Unit tests cover encode/decode round-trips of every enum variant (`protocol_version_wire_byte_roundtrip`, `transport_family_from_tag_bytes_roundtrips_supported_variants`), the probe classifier outputs for synthetic byte traces (`version_probe` test module), and the classifier-class distinctness assertions. Verified green: the four crates pass `cargo test --locked` (92+ passing) and `cargo clippy --locked -- -D warnings` is clean.

## Definition of done

- No remaining `0x01` / `0x05` literal in the version slot of any wire encoder; `cargo clippy --workspace -- -D warnings` clean.
- Probe is callable from the diagnostics CLI and from the Android diagnostic surface (no UI required for this task).

## Risks / open questions

- Hysteria 2 and TUIC do not always echo the server-side version on rejection. For those protocols, `VersionMismatch` may have to be inferred from changelog-known bump points rather than from the wire.
- Distinguishing version-mismatch from active blocking on QUIC paths is inherently noisier; classifier confidence should be surfaced as `Likely(...)` rather than absolute.

## Links

- [[Epic - Control-plane hardening]]
