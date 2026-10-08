## Purpose

Keep diagnostic recommendations tied to the physical network measured throughout the scan.

## ADDED Requirements

### Requirement: REQ-DGN-SCOPE-001 — Require a stable physical network epoch

The implementation MUST accept recommendation authority only when the physical epoch and network scope remain equal to their values before scan preparation. Missing evidence MUST fail closed. A normal RIPDPI VPN stop or start MUST NOT replace the physical epoch.

#### Scenario: Network returns to its starting identity

- **WHEN** the physical network changes from A to B and back to A during a scan
- **THEN** the scan has no recommendation authority even if the final fingerprint matches A

#### Scenario: Stable network

- **WHEN** the physical network remains stable through raw-path VPN stop and start
- **THEN** the scope guard permits existing recommendation rules

#### Scenario: Missing network evidence

- **WHEN** callback evidence is unavailable or the physical network is lost
- **THEN** recommendation authority is withheld

### Requirement: REQ-DGN-SCOPE-002 — Preserve measurements without unsafe effects

The implementation MUST retain invalid-scope measurements with an inconclusive explanation and MUST remove resolver, strategy, nested strategy-probe, and direct-path recommendation authority. It MUST NOT apply temporary overrides, record network policy, DNS preferences, edge or capability evidence, or request a DNS-corrected follow-up from that report. Recovery persistence MUST use the same restriction.

#### Scenario: Changed network at finalization

- **WHEN** a scan finishes on a different network
- **THEN** its report remains available but cannot change settings or create validated network memory

#### Scenario: Recovery report

- **WHEN** an invalid-scope scan returns a partial report during cancellation
- **THEN** the saved report cannot expose an actionable recommendation
