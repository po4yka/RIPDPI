## Purpose

Keep generic direct transport failures distinct from controlled evidence of IP blocking.

## ADDED Requirements

### Requirement: REQ-DIRECT-UNKNOWN-CAUSE — Preserve unknown failure cause

The implementation MUST retain UNKNOWN_DIRECT_FAILURE and no transport class when all attempts fail without mechanism evidence.

#### Scenario: Generic unreachable authority

- **WHEN** direct attempts fail with no protocol-specific mechanism evidence
- **THEN** the verdict and capability record use unknown cause, null transport class, and the existing no-solution cooldown.

#### Scenario: Existing protocol evidence

- **WHEN** TLS or QUIC observations include protocol-specific failure evidence
- **THEN** existing protocol policies remain compatible and no new network identifiers are exposed.
