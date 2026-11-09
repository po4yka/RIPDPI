## Purpose

Keep standard native TLS trust and identity checks explicit, with one diagnostic override policy.

## ADDED Requirements

### Requirement: REQ-STD06-SAN — Verify SAN server identity

Standard verified BoringSSL clients MUST reject CN-only server certificates. They MUST retain chain validation, DNS/IP SAN matching, and SPKI rejection.

#### Scenario: Reject a trusted CN-only leaf

- **WHEN** a trusted server sends a CN-only certificate in TLS 1.2 or TLS 1.3
- **THEN** the client rejects the handshake before application bytes are sent

#### Scenario: Keep valid SAN identities

- **WHEN** a trusted certificate has the expected DNS or IP SAN
- **THEN** the standard client can complete the handshake

### Requirement: REQ-STD06-ROOTS — Use explicit native trust sources

The standard native BoringSSL builder MUST use only bundled Mozilla roots and explicit caller roots. Environment variables and default filesystem paths MUST NOT add trust.

#### Scenario: Reject an environment-only root

- **WHEN** SSL_CERT_FILE or a valid hashed SSL_CERT_DIR supplies a private CA that the caller did not add
- **THEN** its server certificate is rejected

#### Scenario: Keep an explicit caller root

- **WHEN** the caller adds the same private CA through the standard builder
- **THEN** a certificate with a matching SAN can be accepted

### Requirement: REQ-STD06-DIAGNOSTICS — Expose one diagnostic verification policy

Diagnostic TLS helpers MUST use the optional explicit certificate verifier as their only verification override. They MUST NOT expose an unused verification flag. Without an override, default roots and SPKI checks MUST remain active.

#### Scenario: Reject a self-signed diagnostic peer by default

- **WHEN** a diagnostic TLS caller has no custom verifier and the peer has an untrusted certificate
- **THEN** the result reports invalid certificate authentication

#### Scenario: Keep an explicit observational verifier

- **WHEN** an observational caller supplies its no-op verifier
- **THEN** the diagnostic path uses that explicit policy

### Requirement: REQ-STD06-OVERRIDES — Preserve pin-only caller contracts

The change MUST retain explicit WebTunnel chain-pin and DoT pin-only verification. A pin mismatch MUST fail before application data is sent.

#### Scenario: Keep pin-only authentication

- **WHEN** an explicit pin-only caller reaches its matching self-signed peer
- **THEN** it can connect, and a different pin is rejected
