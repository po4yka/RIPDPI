# ShadowTLS Version Policy — ADR

> Status: **decision recorded; v3-only classifier hooks implemented**. Authored: 2026-05-15, refreshed 2026-05-28 against `ripdpi-shadowtls` and `ripdpi-failure-classifier`.

## Question

`ripdpi-shadowtls` ships v3 framing. Should it also support v2 or hard-require v3?

## Decision

**v3 only**, with explicit failure classification.

- v3 handshake (HKDF + HMAC) is the only supported wire.
- v2-shaped failure payloads are classified locally as `ShadowTlsFailureKind::VersionMismatch` in `ripdpi-shadowtls::classify_failure_payload`.
- The shared failure classifier has `FailureClass::ShadowTlsVersionMismatch`, distinct from auth or cert failures.
- User-facing remediation text: "Server speaks ShadowTLS v2; upgrade the server."

## Rationale

- **Upstream guidance.** ihciah/shadow-tls v2 is end-of-life.
- **Wire divergence.** v2 and v3 derive handshake material differently and frame data differently; supporting both is two clients.
- **Diagnostic clarity.** A targeted class is sufficient.

## Trade-offs accepted

- **No v2 connectivity.** Users with v2 servers cannot connect. The ShadowTLS deployer ecosystem migrated to v3 several releases ago, so impact is low.

## Implemented Surface

1. `ripdpi-failure-classifier::FailureClass` includes `ShadowTlsVersionMismatch` and pins its stable string as `shadowtls_version_mismatch`.
2. `ripdpi-shadowtls::classify_failure_payload` detects the v2 TLS-record-at-offset-0 shape and returns `ShadowTlsFailureKind::VersionMismatch`.
3. Unit tests pin the v2 signature and ensure normal v3 HMAC-prefixed payloads pass through as `Other`.

## Runtime wiring (DONE)

`ShadowTlsFailureKind::VersionMismatch` is now constructed at runtime (PR #162):

1. `ripdpi-shadowtls` exposes a typed `ShadowTlsHandshakeError` (mirroring `ripdpi_tuic::TuicHandshakeError`) and constructs it at the handshake-failure seam in `drive_handshake_to_application_data`.
2. The detection point is the **post-ServerHello frame**, not the ServerHello itself (which is a raw `0x16 0x03` record for *both* v2 and v3, so classifying it would false-positive every connection). v3 sends the HMAC-authenticated application-data switch (`0x17`) there; a v2/non-v3 server presents a raw handshake record (`0x16 0x03`), which `classify_failure_payload` recognises.
3. `ripdpi-relay-core` downcasts the typed error and maps it to `FailureClass::ShadowTlsVersionMismatch`, leading the recorded `last_handshake_error` string with the `shadowtls_version_mismatch` token. Applied on the direct ShadowTLS backend and on chain relays with a ShadowTLS hop.

### Detection is best-effort, not exhaustive

The signal is a heuristic on observed wire bytes. It fires only when a valid ServerHello is followed by a raw `0x16 0x03` handshake record — the v2 shape this ADR assumes. A v2 deployment whose first post-ServerHello frame is shaped differently (e.g. an encrypted `0x17` record, or a `0x14` ChangeCipherSpec first) is **not** detected and falls back to the generic handshake error. That is a *safe* miss (no false positive, just no actionable hint), and it is symmetric with the auth-failure case: a v3 server rejecting a bad password sends a `0x17` switch frame whose HMAC fails, classifies as `Other`, and is never misread as a version mismatch.

## Owner

Native-transport owner. Runtime wiring landed in PR #162.
