## Purpose

Keep relay profile activation and native configuration decoding consistent across the Kotlin and Rust boundary.

## ADDED Requirements

### Requirement: REQ-RLY-1794335295387247-001 — Explicit native activation selects Native

The implementation MUST atomically select the Native VPN provider when a user explicitly activates a native relay. Store-only updates MUST preserve the selected provider and saved Xray profiles.

#### Scenario: Activate a native relay after Xray

- **WHEN** Xray is selected and a native relay activation succeeds
- **THEN** the committed provider is Native and the saved Xray profile remains available

### Requirement: REQ-RLY-1794335295387247-002 — Validate imported endpoints before persistence

The implementation MUST reject Trojan and AnyTLS profiles with invalid endpoints before it changes stored profiles, credentials, settings, or selection.

#### Scenario: Import an out-of-range server port

- **WHEN** a Trojan or AnyTLS node contains a port outside 1 through 65535
- **THEN** validation rejects the node and existing durable state remains intact

#### Scenario: Invalid subscription refresh preserves the old group

- **WHEN** a refresh contains an invalid Trojan or AnyTLS endpoint
- **THEN** it reports a parse error before group replacement and preserves the previous members and settings

### Requirement: REQ-RLY-1794335295387247-003 — Require the current native schema for every relay

The implementation MUST require native relay schema 10 before JNI session dispatch for Standard and Apps Script relays. Existing supported Apps Script aliases and session credential rules MUST remain intact.

#### Scenario: Reject a missing or non-current schema

- **WHEN** a JNI relay payload omits schemaVersion or carries a retired or future version
- **THEN** no relay session or listener is created

### Requirement: REQ-RLY-1794335295387247-004 — Reject unknown Reality transports

The implementation MUST reject a raw Reality transport other than reality_tcp or xhttp before it publishes readiness. Supported flow and UDP behavior MUST stay unchanged.

#### Scenario: Unknown transport cannot select TCP

- **WHEN** a Reality payload has a blank or unknown transport token
- **THEN** backend construction fails and no listener is ready

### Requirement: REQ-RLY-1794335295387247-005 — Check the real configuration boundary

The contract tests MUST detect missing registry rows and incompatible current producer fields. Tests MUST distinguish actual serialization or backend behavior from recorded fake calls. Audit hypotheses MUST be resolved from observed behavior before implementation changes their semantics.

#### Scenario: A producer or registry changes

- **WHEN** a current producer emits a field or a registry row that its consumer cannot represent
- **THEN** a maintained contract check fails with the affected field or kind

### Requirement: REQ-RLY-1794335295387247-006 — Read one relay profile generation

The implementation MUST read a stored relay profile and its credentials in the same recovered-read scope as the mutation coordinator.

#### Scenario: A mutation races with runtime resolution

- **WHEN** a relay mutation begins between the profile and credential reads
- **THEN** runtime resolution receives a matching endpoint and credential generation and the mutation can complete after that read scope ends

### Requirement: REQ-RLY-1794335295387247-007 — Plan capabilities from the active submode

The implementation MUST report the same planned reuse capability as the actual backend for a valid Mieru or Reality mux submode.

#### Scenario: Mux changes connection reuse

- **WHEN** Mieru mux is off or Reality TCP uses yamux
- **THEN** the planned reuse flag matches the built backend without adding a new transport mode
