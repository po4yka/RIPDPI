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

## Remaining Work

Map `ShadowTlsFailureKind::VersionMismatch` at runtime wherever ShadowTLS handshake failures are converted into user-facing failure classes.

## Owner

Native-transport owner picks up the implementation work.
