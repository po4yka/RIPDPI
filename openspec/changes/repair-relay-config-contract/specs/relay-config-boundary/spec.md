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
