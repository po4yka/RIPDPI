## Purpose

Define the observable completion contract for Epic - Extended outbound protocol support. > 2026-06-01 — scope reduced per ADR 0004. VMess, Trojan-Go, and Hysteria v1 are dropped from this epic and removed from the codebase — they were never-completed stubs that carried no traffic, and RIPDPI maintains support only for current/actual protocols. The remaining open backlog is SSH and Mieru only (not-yet-implemented compatibility work, explicitly not legacy). Their child tasks are deleted

## ADDED Requirements

### Requirement: REQ-EPC-1786264762917457-001 — Each protocol has a profile-edit screen with schema-backed validation. (SshProf…

The RIPDPI implementation MUST satisfy this portfolio criterion: Each protocol has a profile-edit screen with schema-backed validation. (SshProfileScreen.kt, MieruProfileScreen.kt, AnyTlsProfileScreen.kt under app/src/main/kotlin/com/poyka/ripdpi/ui/screens/.).

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Each protocol has a profile-edit screen with schema-backed validation. (SshProfileScreen.kt, MieruProfileScreen.kt, AnyTlsProfileScreen.kt under app/src/main/kotlin/com/poyka/ripdpi/ui/screens/.)

### Requirement: REQ-EPC-1786264762917457-002 — Each protocol can be parsed from its standard URI scheme into a valid RIPDPI pr…

The RIPDPI implementation MUST satisfy this portfolio criterion: Each protocol can be parsed from its standard URI scheme into a valid RIPDPI profile and round-tripped back to URI. (anytls:// + mieru:// pre-existing; ssh:// added 2026-06-11 — first-class ProxyProfile.Ssh + parseSsh/encodeSsh round-trip incl. multi-line pri….

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Each protocol can be parsed from its standard URI scheme into a valid RIPDPI profile and round-tripped back to URI. (anytls:// + mieru:// pre-existing; ssh:// added 2026-06-11 — first-class ProxyProfile.Ssh + parseSsh/encodeSsh round-trip incl. multi-line pri…

### Requirement: REQ-EPC-1786264762917457-003 — Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan i…

The RIPDPI implementation MUST satisfy this portfolio criterion: Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS). (StrategyPackProtocolHint + bundled catalog.json ssh/mieru/anytls entries, load-bearing via StrategyPackSnapshot.protocolHints / hintForPr….

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Strategy-pack metadata includes per-protocol compatibility hints (e.g. Trojan inside xHTTP, SSH direct vs SSH-over-TLS). (StrategyPackProtocolHint + bundled catalog.json ssh/mieru/anytls entries, load-bearing via StrategyPackSnapshot.protocolHints / hintForPr…

### Requirement: REQ-EPC-1786264762917457-004 — Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and…

The RIPDPI implementation MUST satisfy this portfolio criterion: Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and crash reports, not only at export time. (SSH + Mieru redact in Debug (pre-existing); AnyTLS closed 2026-06-11 — Rust Debug for AnyTlsClientConfig masks password + root cert (commi….

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Secrets (passwords, UUIDs, private keys) are redacted in logs, diagnostics, and crash reports, not only at export time. (SSH + Mieru redact in Debug (pre-existing); AnyTLS closed 2026-06-11 — Rust Debug for AnyTlsClientConfig masks password + root cert (commi…

### Requirement: REQ-EPC-1786264762917457-005 — Remaining protocols pass upstream interoperability oracles

Every remaining protocol implementation MUST pass an upstream reference vector or a live-server interoperability test before the epic can complete.

#### Scenario: Verify protocol interoperability

- **WHEN** each remaining protocol is exercised against its selected upstream oracle
- **THEN** the observable handshake and payload exchange MUST pass

### Requirement: REQ-EPC-1786264762917457-006 — Protocol supervisors shut down cleanly

Every protocol supervisor MUST start and stop cleanly and join bounded handler work, including modes that remain incomplete elsewhere in the epic.

#### Scenario: Verify bounded supervisor lifecycle

- **WHEN** each protocol runtime is started and then asked to stop under load
- **THEN** its handlers MUST terminate within the documented bound without leaked work
