## Purpose

Keep domain TLS reports consistent with observed transport stages and the final probe attempt.

## ADDED Requirements

### Requirement: REQ-DGN-TLS-001 — Preserve failure stage

The implementation MUST require a measured TLS handshake failure stage before it creates a TLS handshake failure diagnosis.

#### Scenario: TCP connection timeout

- **WHEN** a TCP connection times out before TLS starts
- **THEN** the raw error and TCP stage remain visible and no ClientHello diagnosis is produced.

#### Scenario: Missing stage in an older report

- **WHEN** an older report has a TLS error without a failure stage
- **THEN** the report remains readable but does not assert a measured handshake failure.

### Requirement: REQ-DGN-TLS-002 — Keep retry evidence consistent

The implementation MUST use the successful retry observation for TLS status, error, version, and connection metadata, while retaining failed attempts only in their specific profile fields.

#### Scenario: Retry succeeds after total failure

- **WHEN** the final TLS retry succeeds
- **THEN** the aggregate TLS observation is successful and has no stale failure or ClientHello diagnosis.
