## Purpose

Define bounded cross-scan confirmation for fingerprint and concurrency evidence
without persisting raw targets or stable user/network identifiers.

## ADDED Requirements

### Requirement: REQ-DGN-1786299732336499-001 — Cross-scan evidence is independent and fresh

The diagnostic MUST confirm across scans only when eligible observations are
fresh, scope-consistent, and associated with different stable target aliases.

#### Scenario: Two independent scans support the conjunction

- **GIVEN** two fresh eligible scans in one hashed scope with distinct stable aliases
- **WHEN** the assessment is recomputed
- **THEN** the conjunction MAY become confirmed without exposing either raw target

### Requirement: REQ-DGN-1786299732336499-002 — Persisted history is privacy bounded

Persisted history MUST contain only categorical evidence, bounded time, hashed
scope, stable alias, eligibility, and schema metadata.

#### Scenario: History is exported or archived

- **GIVEN** stored cross-scan evidence
- **WHEN** it is inspected through database, backup, archive, UI, or export
- **THEN** no raw host, address, SNI, interface, or network identifier MAY appear

### Requirement: REQ-DGN-1786299732336499-003 — Incomplete evidence stays non-actionable

One clean target and stale, partial, cancelled, ineligible, or scope-mismatched
observations MUST NOT confirm the conjunction.

#### Scenario: A scan is cancelled after partial observations

- **GIVEN** one prior eligible result and a newly cancelled scan
- **WHEN** assessment runs
- **THEN** the previous result MUST remain insufficient and no learned policy MAY be activated

### Requirement: REQ-DGN-1786299732336499-004 — Lifecycle operations invalidate history

Retention expiry, network-scope change, full reset, and relevant backup restore
MUST remove or invalidate cross-scan history deterministically.

#### Scenario: User performs a full reset

- **GIVEN** persisted cross-scan history
- **WHEN** full reset completes
- **THEN** no history record or derived confirmation MAY remain
