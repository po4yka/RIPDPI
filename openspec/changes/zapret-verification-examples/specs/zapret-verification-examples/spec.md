## Purpose

Use upstream packet examples to verify native packet handling and run complete diagnostic candidate audits within the current scan limits.

## ADDED Requirements

### Requirement: REQ-VERIFY-001 — Pinned packet examples

Maintained tests MUST check IPv4/IPv6, TCP options, checksums and full Lua strategy outputs against deterministic vectors produced by pinned upstream helpers.

#### Scenario: Option-bearing split

- **WHEN** Lua splits an option-bearing TCP packet with an odd-length payload and wrapping sequence number
- **THEN** the complete outputs match upstream lengths, headers and checksums; unsupported headers remain safe passthrough.

### Requirement: REQ-VERIFY-002 — Complete applicable matrix

An uncapped full_matrix_v1 audit MUST retain all applicable TCP and QUIC candidates without success or pilot pruning. Quick and explicitly capped scans MUST retain their existing selection policy.

#### Scenario: Confirmed QUIC or failed pilot

- **WHEN** an uncapped full audit sees promotable QUIC evidence or a candidate fails its pilot targets
- **THEN** applicable TCP candidates remain in the executed matrix.

### Requirement: REQ-VERIFY-003 — Bounded and explicit incomplete audit

Full audits MUST retain existing deadlines and cancellation and MUST report PartialResults when unfinished, including DNS fallback runs.

#### Scenario: Budget exhaustion

- **WHEN** an uncapped full audit reaches its active budget before the matrix completes
- **THEN** it stops within the existing budget and reports an incomplete pass rather than complete or DNS-only acceptance.
