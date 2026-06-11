---
title: Wire ShadowTLS version-mismatch into service telemetry
type: task
status: todo
area: rust-native
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-11
updated: 2026-06-11
---

## Summary

`FailureClass::ShadowTlsVersionMismatch` exists in `ripdpi-failure-classifier`
but is **never constructed at runtime** — the exact gap that
`add-tuic-v4-fallback-or-version-detection` just closed for TUIC. The variant
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

- [ ] ShadowTLS exposes a typed handshake error (mirroring `TuicHandshakeError`)
      that classifies a v2-server reject as a version mismatch on the
      handshake-failure path.
- [ ] `ripdpi-relay-core` (or the ShadowTLS transport wrapper) downcasts it and
      constructs `FailureClass::ShadowTlsVersionMismatch`, surfacing the
      `shadowtls_version_mismatch` token + a server-upgrade diagnostic into
      service telemetry.
- [ ] A runtime-path test drives the real client against a v2-reject loopback
      fixture and asserts the typed version-mismatch error (not a generic TLS
      handshake failure). Include the negative case: an ordinary TLS/auth
      rejection must NOT be misclassified as a version mismatch.

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

- [[add-tuic-v4-fallback-or-version-detection]] (the shipped sibling that
  established the pattern)
