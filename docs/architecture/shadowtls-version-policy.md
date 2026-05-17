# ShadowTLS Version Policy — ADR

> Status: **draft; awaiting code implementation**. Authored: 2026-05-15. Tracking task: `docs/tasks/issues/add-shadowtls-v2-compatibility-or-document-v3-only.md`.

## Question

`ripdpi-shadowtls` ships v3 framing. Should it also support v2 or hard-require v3?

## Decision

**v3 only**, with explicit failure classification.

- v3 handshake (HKDF + HMAC) is the only supported wire.
- v2-server responses are classified as `FailureClass::ShadowTlsVersionMismatch` in the failure classifier, distinct from auth or cert failures.
- User-facing remediation text: "Server speaks ShadowTLS v2; upgrade the server."

## Rationale

- **Upstream guidance.** ihciah/shadow-tls v2 is end-of-life.
- **Wire divergence.** v2 and v3 derive handshake material differently and frame data differently; supporting both is two clients.
- **Diagnostic clarity.** A targeted class is sufficient.

## Trade-offs accepted

- **No v2 connectivity.** Users with v2 servers cannot connect. The ShadowTLS deployer ecosystem migrated to v3 several releases ago, so impact is low.

## Implementation outline

1. In `ripdpi-failure-classifier`, add `ShadowTlsVersionMismatch`.
2. In `ripdpi-shadowtls::handshake`, detect v2-server HMAC-tag shape on rejection and surface `ShadowTlsVersionMismatch`.
3. Add a unit test that feeds a v2-style response into the v3 handshake path and asserts the classifier output.

## Owner

Native-transport owner picks up the implementation work.
