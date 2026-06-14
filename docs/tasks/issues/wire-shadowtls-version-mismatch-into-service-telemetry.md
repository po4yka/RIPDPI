---
title: Wire ShadowTLS version-mismatch into service telemetry
type: task
status: in-review
area: rust-native
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-11
updated: 2026-06-14
---

## Summary

`FailureClass::ShadowTlsVersionMismatch` exists in `ripdpi-failure-classifier`
but is **never constructed at runtime** — the exact gap that the TUIC
v4 version-detection work just closed for `FailureClass::TuicVersionUnsupported`
(shipped in commit `3ead52203`; the task file is deleted per the board's
done-task convention, git history is the audit trail). The variant
appears only in the enum definition, its `as_str()`, the
`response_triggers.rs` `=> 0` arm, a doc comment in `ripdpi-shadowtls/src/lib.rs`,
and tests. A ShadowTLS v2 server talking to this v3-only client produces a
generic TLS handshake error instead of the user-actionable "upgrade your
ShadowTLS server to v3" diagnostic the variant was created to surface.

## Context

This is the direct sibling of the shipped TUIC work. The TUIC fix established
the pattern:

1. The protocol crate (`ripdpi-tuic`) stays free of `ripdpi-failure-classifier`
   and instead exposes a typed handshake error (`TuicHandshakeError`) carrying a
   coarse failure kind, classified on the handshake-failure path from observed
   wire bytes / the QUIC application-close reason.
2. The relay backend (`ripdpi-relay-core/src/protocols/<proto>.rs`) downcasts the
   typed error and, on a version mismatch, constructs the `FailureClass` variant
   and surfaces a token-led, user-actionable diagnostic through the existing
   `record_handshake_error` → `last_handshake_error` service-telemetry path.

ShadowTLS reaches the relay path via `ripdpi-relay-tls-transports` /
`ripdpi-shadowtls`; identify the v2-vs-v3 detection seam (HMAC / handshake
framing in `ripdpi-shadowtls/src/handshake.rs`) before writing claims.

## Acceptance criteria

- [x] ShadowTLS exposes a typed handshake error (`ShadowTlsHandshakeError`,
      mirroring `TuicHandshakeError`) that classifies a v2-server reject as a
      version mismatch on the handshake-failure path.
- [x] `ripdpi-relay-core` downcasts it and constructs
      `FailureClass::ShadowTlsVersionMismatch`, surfacing the
      `shadowtls_version_mismatch` token + a server-upgrade diagnostic into
      service telemetry (`record_handshake_error` → `last_handshake_error`).
- [x] A runtime-path test drives the real client against a v2-reject loopback
      fixture and asserts the typed version-mismatch error (not a generic TLS
      handshake failure), plus the negative case (bad-password v3 reject is NOT
      misclassified).

## Implementation notes (2026-06-14)

Most of the scaffolding was already shipped (`ShadowTlsFailureKind`,
`classify_failure_payload`, the `FailureClass` variant + `shadowtls_version_mismatch`
token); this closed the ADR's "Remaining Work": runtime construction.

1. **Detection seam** (`ripdpi-shadowtls/src/client.rs`,
   `drive_handshake_to_application_data`): the ServerHello is a raw `0x16 0x03`
   TLS record for **both** v2 and v3, so it must NOT be classified — it is read
   and validated *outside* the loop. The real seam is the post-ServerHello frame
   at the switch point: v3 sends the HMAC-authenticated application-data switch
   (`0x17`); a v2/non-v3 server presents a raw handshake record (`0x16 0x03`).
   When `classify_failure_payload(&frame) == VersionMismatch` there, return
   `io::Error::other(ShadowTlsHandshakeError::version_mismatch())`.
2. **No false positive on auth failures:** a v3 bad-password reject sends a `0x17`
   switch frame whose HMAC fails `verify_handshake_frame` (the `0x17` arm), never
   reaching the `0x16 0x03` detection branch. A middlebox CCS (`0x14`) classifies
   as `Other`. Covered by `shadowtls_bad_password_is_not_classified_as_version_mismatch`.
3. **Typed-error preservation:** `ripdpi-relay-tls-transports` previously flattened
   the error into a string `io::Error`, destroying the downcast. `wrap_shadowtls_connect_error`
   now passes a typed `ShadowTlsHandshakeError` through unchanged (both `connect`
   paths), and the crate re-exports the type so relay-core downcasts it without a
   new dependency edge on `ripdpi-shadowtls`.
4. **Token mapping** (`ripdpi-relay-core/src/protocols/shadowtls.rs`, mirror of
   `tuic.rs`): applied on `connect_tcp` for the `ShadowTls` backend **and**
   `ChainRelay` (a ShadowTLS entry/exit hop) — the mapper is a no-op for any
   non-ShadowTLS error, so chain coverage is safe and closes the chain-hop gap an
   adversarial review surfaced.
5. **Tests:** real-client-vs-v2-reject-loopback (`ShadowTlsV2RejectLoopback`) →
   typed version mismatch; bad-password negative; relay-core token-mapping +
   pass-through unit tests. `cargo nextest -p ripdpi-shadowtls -p ripdpi-relay-core
   -p ripdpi-relay-tls-transports -p ripdpi-failure-classifier --locked`: green;
   clippy `-D warnings` clean (incl. the `test-server` feature).

## Definition of done

- A ShadowTLS v2-server connection attempt produces a user-actionable
  diagnostic, not a generic TLS handshake error.

## Risks / open questions

- Unlike TUIC (a clean leading version byte), ShadowTLS v2/v3 differ in HMAC
  derivation / handshake framing — the "recognised version reject" signal is
  less obvious. Scope the detection seam carefully and avoid the false-positive
  trap (a v3 server rejecting a bad password must not read as a version
  mismatch). See `docs/architecture/shadowtls-version-policy.md`.

## Links

- TUIC v4 version-detection (the shipped sibling that established the pattern):
  closed task, landed in commits `02292caa2` / `96e32784e` / `3ead52203`; see
  `docs/architecture/tuic-v4-policy.md` § "Runtime wiring (DONE)".
