## Purpose

Keep each stored network snapshot within one verified observation scope.

## ADDED Requirements

### Requirement: REQ-METADATA-SCOPE — Reject mixed captures

The implementation MUST reject network metadata when physical epoch or calling-default path changes during capture. It MUST NOT serialize epoch tokens or network identities.

#### Scenario: Network changes during public IP lookup

- **WHEN** the calling-default network changes while the public IP resolver is suspended
- **THEN** the snapshot records unavailable capture evidence and omits public IP, transport details, and path observations

#### Scenario: Network changes back

- **WHEN** physical epoch changes from A to B and back to A
- **THEN** the monotonic epoch difference invalidates the snapshot

#### Scenario: Stable network

- **WHEN** all path evidence remains unchanged across capture
- **THEN** the snapshot preserves the observed metadata
