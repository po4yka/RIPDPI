## Purpose

Define typed, privacy-safe connection-freeze evidence and fail-safe retry
behavior without converting uncertain observations into censorship claims.

## ADDED Requirements

### Requirement: REQ-DGN-1786299627046211-001 — Freeze observations have typed phases

The classifier MUST distinguish pre-handshake silence, handshake-stage freeze,
post-data stall, and unknown phase using typed evidence.

#### Scenario: No response follows the client handshake message

- **GIVEN** an established connection with a sent client handshake and no server response
- **WHEN** bounded stall observations satisfy the classifier
- **THEN** the result MUST carry handshake-stage freeze evidence rather than a generic string tag

### Requirement: REQ-DGN-1786299627046211-002 — Refinement preserves matrix compatibility

Freeze refinement MUST survive Rust/Kotlin serialization and exports while the
coarse block-signal matrix key remains unchanged.

#### Scenario: Refined evidence enters the block matrix

- **GIVEN** a typed post-data freeze observation
- **WHEN** it is mapped to block evidence and serialized
- **THEN** the coarse signal MUST remain connection-freeze and the typed phase MUST remain available alongside it

### Requirement: REQ-DGN-1786299627046211-003 — Retry guard is disabled by default

The runtime MUST preserve existing retry decisions when no freeze guard policy
is configured and MUST NOT embed an externally reported duration as a default.

#### Scenario: Existing configuration has no guard

- **GIVEN** a configuration without a freeze retry guard
- **WHEN** a connection-freeze signal is processed
- **THEN** serialized configuration and retry selection MUST remain behaviorally compatible

### Requirement: REQ-DGN-1786299627046211-004 — Confirmed freezes suppress unsafe retries

When explicitly enabled and confirmed, the guard MUST suppress immediate
same-destination retry and transport-fingerprint diversification within its
privacy-preserving network/authority scope.

#### Scenario: Confirmed freeze is inside its observed guard window

- **GIVEN** confirmed typed freeze evidence and an enabled guard
- **WHEN** retry selection evaluates the same scope
- **THEN** neither the same destination nor a fingerprint-diversifying alternative MAY be selected until the guard expires
