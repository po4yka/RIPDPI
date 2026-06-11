# TUIC v4 Policy — ADR

> Status: **decision recorded; v5-only classification AND runtime mapping implemented**. Authored: 2026-05-15, refreshed 2026-06-11 against `ripdpi-tuic`, `ripdpi-relay-core`, and `ripdpi-failure-classifier`.

## Question

`ripdpi-tuic` ships TUIC v5 only (`TUIC_VERSION = 0x05`). Should it also support v4, negotiate, or hard-require v5?

## Decision

**v5 only**, with explicit failure classification.

- The wire constant remains `TUIC_VERSION = 0x05`.
- Non-v5 failure payloads are classified locally as `TuicFailureKind::VersionUnsupported` in `ripdpi-tuic::classify_failure_payload`.
- The shared failure classifier has `FailureClass::TuicVersionUnsupported`, distinct from generic protocol errors.
- User-facing remediation text: "Server speaks TUIC v4; upgrade the server or remove this profile."

## Rationale

- **Population.** v5 has been the recommended deployment since 2023. Active v4 servers are rare; the maintenance cost of dual-version wire code is high.
- **Wire divergence.** v4 and v5 differ on auth, packet framing, and command bytes. "Fallback" effectively means shipping two clients.
- **Diagnostic clarity.** A targeted `TuicVersionUnsupported` class is enough to direct the operator without carrying the v4 wire.

## Trade-offs accepted

- **No v4 connectivity.** Users with v4-only servers cannot connect. This is documented in the editor UI and surfaced in the failure classifier.

## Implemented Surface

1. `ripdpi-failure-classifier::FailureClass` includes `TuicVersionUnsupported` and pins its stable string as `tuic_version_unsupported`.
2. `ripdpi-tuic::ProtocolVersion` supports only `V5`; `TUIC_VERSION` is derived from it.
3. `ripdpi-tuic::classify_failure_payload` maps any non-v5 leading version byte to `TuicFailureKind::VersionUnsupported`.
4. Unit tests pin the v4 byte path, the v5 pass-through path, and arbitrary non-v5 bytes.

## Runtime wiring (DONE — 2026-06-11)

The runtime mapping is implemented, wired, and tested:

- `ripdpi-tuic::TuicHandshakeError` + `classify_handshake_failure` (`protocol.rs`) construct `TuicHandshakeError::version_unsupported()` from a QUIC application-close reason whose leading byte is the legacy v4 wire byte (`0x04`), on the `connect` / `tcp_connect` handshake-failure path. The gate is narrow so a v5 server closing with a free-form reason (e.g. bad credentials) is not misread.
- `ripdpi-relay-core::protocols::tuic::classify_tuic_handshake_error` downcasts the typed error and constructs `FailureClass::TuicVersionUnsupported`, emitting the `tuic_version_unsupported` token + actionable "upgrade to v5" text. Applied at `open_stream`, `open_datagram`, and `create_session`; it flows through the SOCKS `record_handshake_error → last_handshake_error` telemetry to the service diagnostic surface.

Landed in commits `02292caa2` (feat(tuic)), `96e32784e` (feat(relay-core)), `3ead52203` (docs(tasks) close).

A parallel gap remains for `FailureClass::ShadowTlsVersionMismatch`, which has the same never-constructed-at-runtime shape — tracked in `docs/tasks/issues/wire-shadowtls-version-mismatch-into-service-telemetry.md`.
