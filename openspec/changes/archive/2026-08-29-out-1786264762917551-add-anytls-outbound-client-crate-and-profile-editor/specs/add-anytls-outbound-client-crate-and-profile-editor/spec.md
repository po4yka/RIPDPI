## Purpose

Define the observable completion contract for Finish AnyTLS profile editor and compatibility gaps. AnyTLS is now a first-class relay kind with a Rust crate, relay-core backend, URI/subscription import support, and runtime config fields. Keep this task for the remaining UI and compatibility polish that is not yet present in the codebase

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917551-001 — ripdpi-anytls crate exists with frame, padding, and TLS-session tests

The RIPDPI implementation MUST satisfy this portfolio criterion: ripdpi-anytls crate exists with frame, padding, and TLS-session tests.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that ripdpi-anytls crate exists with frame, padding, and TLS-session tests

### Requirement: REQ-OUT-1786264762917551-002 — Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TC…

The RIPDPI implementation MUST satisfy this portfolio criterion: Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TCP plus UDP-over-TCP fixtures.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Relay-core builds an AnyTLS backend, validates it as UDP-capable, and covers TCP plus UDP-over-TCP fixtures

### Requirement: REQ-OUT-1786264762917551-003 — anytls://, Clash anytls, and Sing-box anytls imports map to first-class profiles

The RIPDPI implementation MUST satisfy this portfolio criterion: anytls://, Clash anytls, and Sing-box anytls imports map to first-class profiles.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that anytls://, Clash anytls, and Sing-box anytls imports map to first-class profiles

### Requirement: REQ-OUT-1786264762917551-004 — Relay native config carries AnyTLS password and root-certificate fields

The RIPDPI implementation MUST satisfy this portfolio criterion: Relay native config carries AnyTLS password and root-certificate fields.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Relay native config carries AnyTLS password and root-certificate fields

### Requirement: REQ-OUT-1786264762917551-005 — Cross-interop against upstream anytls-go is verified and recorded. (deferred: l…

The RIPDPI implementation MUST satisfy this portfolio criterion: Cross-interop against upstream anytls-go is verified and recorded. (deferred: live-server only; offline-infeasible nightly oracle.).

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Cross-interop against upstream anytls-go is verified and recorded. (deferred: live-server only; offline-infeasible nightly oracle.)

### Requirement: REQ-OUT-1786264762917551-006 — Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported…

The RIPDPI implementation MUST satisfy this portfolio criterion: Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported behavior is rejected explicitly. (RIPDPI's client has no server-side TLS fallback; ProxyUriCodec.parseAnyTls now explicitly rejects anytls:// nodes advertising a fallback/fallback….

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Fallback-SNI and fallback-server behavior matches upstream spec, or unsupported behavior is rejected explicitly. (RIPDPI's client has no server-side TLS fallback; ProxyUriCodec.parseAnyTls now explicitly rejects anytls:// nodes advertising a fallback/fallback…

### Requirement: REQ-OUT-1786264762917551-007 — AnyTLSProfileScreen validates password length, server + port, and server-name (…

The RIPDPI implementation MUST satisfy this portfolio criterion: AnyTLSProfileScreen validates password length, server + port, and server-name (SNI).

#### Scenario: Verify criterion 7

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that AnyTLSProfileScreen validates password length, server + port, and server-name (SNI)

### Requirement: REQ-OUT-1786264762917551-008 — Main Mode Editor exposes AnyTLS fields instead of relying only on import/profil…

The RIPDPI implementation MUST satisfy this portfolio criterion: Main Mode Editor exposes AnyTLS fields instead of relying only on import/profile records. (deferred: AnyTLS is fully configurable via the dedicated AnyTlsProfileScreen + import; exposing it inline is a separate end-to-end "make AnyTLS a selectable+serializabl….

#### Scenario: Verify criterion 8

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Main Mode Editor exposes AnyTLS fields instead of relying only on import/profile records. (deferred: AnyTLS is fully configurable via the dedicated AnyTlsProfileScreen + import; exposing it inline is a separate end-to-end "make AnyTLS a selectable+serializabl…

### Requirement: REQ-OUT-1786264762917551-009 — Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-h…

The RIPDPI implementation MUST satisfy this portfolio criterion: Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-heavy neighborhoods. (StrategyPackProtocolHint + bundled catalog.json anytls entry with quicHeavyNeighborhood: true, surfaced via StrategyPackSnapshot.protocolHints / hintForProtoco….

#### Scenario: Verify criterion 9

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Strategy-pack metadata advertises AnyTLS compat hints, especially around QUIC-heavy neighborhoods. (StrategyPackProtocolHint + bundled catalog.json anytls entry with quicHeavyNeighborhood: true, surfaced via StrategyPackSnapshot.protocolHints / hintForProtoco…

### Requirement: REQ-OUT-1786264762917551-010 — Password is redacted in all diagnostic surfaces. (Rust: hand-written Debug for…

The RIPDPI implementation MUST satisfy this portfolio criterion: Password is redacted in all diagnostic surfaces. (Rust: hand-written Debug for AnyTlsClientConfig masks password + root cert. Kotlin: ProxyProfile.AnyTls.toString masks the password.).

#### Scenario: Verify criterion 10

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Password is redacted in all diagnostic surfaces. (Rust: hand-written Debug for AnyTlsClientConfig masks password + root cert. Kotlin: ProxyProfile.AnyTls.toString masks the password.)
