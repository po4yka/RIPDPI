## Purpose

Report DNS observations without attributing ordinary resolver variation to interference.

## ADDED Requirements

### Requirement: REQ-HOME-DNS-001 — Preserve DNS uncertainty

The implementation MUST treat disjoint public DNS answers and DNS lookup failures as inconclusive. It MUST NOT infer transparent proxy detection from answer differences or endpoint failure.

#### Scenario: Different CDN answers

- **WHEN** system and encrypted resolvers return different public addresses
- **THEN** Home reports unknown DNS status with divergence notes and no poisoned hosts.

#### Scenario: DNS failure

- **WHEN** a probe reports a DNS resolution failure
- **THEN** the block layer is unknown with low confidence.
