## Purpose

Define cleanup isolation for sequential replacement and explicit parallel candidate races without confusing their startup order.

## MODIFIED Requirements

### Requirement: REQ-RLY-1786707070050078-005 — Candidate transitions preserve lifecycle isolation

Sequential replacement MUST finish and await cleanup of the previous relay before starting its successor. An explicit parallel core race MAY start its eligible candidates together, but MUST finish loser cleanup before it publishes a selected result. Failed cleanup MUST remain owned for retry and prevent successful selection. The selected TCP-only session MUST keep UDP ASSOCIATE disabled.

#### Scenario: Sequential replacement starts a successor

- **WHEN** the active candidate is confirmed failed and a replacement is selected
- **THEN** the previous runtime, jobs, sockets, and listeners are stopped before the replacement enters its startup section

#### Scenario: A parallel race loser cannot stop

- **WHEN** a losing runtime reports failed or pending cleanup
- **THEN** the race does not publish successful selection and retains all unclosed runtimes for shutdown retry

#### Scenario: TCP-only fallback is selected

- **WHEN** the effective plan requires TCP connect but not UDP associate
- **THEN** both the relay active probe and session-local proxy configuration disable UDP ASSOCIATE

#### Scenario: UDP target is absent

- **WHEN** UDP verification is requested without an explicit safe UDP target
- **THEN** the missing target is reported as inconclusive and is not classified as relay failure

