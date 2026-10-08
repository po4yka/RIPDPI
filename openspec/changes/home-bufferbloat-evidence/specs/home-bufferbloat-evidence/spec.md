## Purpose

Report bufferbloat grades only when latency is measured during verified data transfer.

## ADDED Requirements

### Requirement: REQ-HOME-BLOAT-001 — Require measured concurrent load

The implementation MUST report UNKNOWN without a successful nonempty transfer that overlaps RTT measurement. It MUST retain idle evidence and omit unsupported loaded latency and delta.

#### Scenario: Transfer failed or empty

- **WHEN** the load request fails or returns no bytes
- **THEN** the bufferbloat grade is UNKNOWN.

#### Scenario: No overlap

- **WHEN** all RTT samples occur outside the transfer interval
- **THEN** the bufferbloat grade is UNKNOWN.

#### Scenario: Measured load

- **WHEN** successful nonempty transfer overlaps RTT samples
- **THEN** the grade uses only overlapping samples.
