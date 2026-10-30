## Purpose

Keep SSH observation results behind the service facade while preserving native error classification and explicit user trust confirmation.

## ADDED Requirements

### Requirement: REQ-SERVICE-BOUNDARY — Own the observation contract

SshHostKeyObserver MUST return service-owned results. App production and unit-test code MUST NOT import engine probe types. The existing dependency and import guard MUST remain unchanged.

#### Scenario: SSH editor receives an observation

- **WHEN** the native adapter returns a key or a typed failure
- **THEN** the service returns the corresponding facade result without exposing an engine type to the editor

### Requirement: REQ-OBSERVATION-PARITY — Preserve behavior

The conversion MUST preserve fingerprint and algorithm values and all failure meanings. Cancellation, underlay ownership, socket protection, deadlines, and explicit trust confirmation MUST retain their current behavior.

#### Scenario: Native or service operation fails

- **WHEN** native status, missing underlay, revoked network, or busy operation produces a failure
- **THEN** the public observation reports the matching service failure and the previous trusted key remains unchanged

### Requirement: REQ-VALIDATION — Observe quality gates

The fix MUST pass full local staticAnalysis and affected tests without gate exclusions, baseline changes, or allowlist exceptions. Hosted CI and CodeQL MUST be checked for the pushed implementation revision.

#### Scenario: Fixed revision is verified

- **WHEN** local checks finish and the revision is pushed
- **THEN** the handoff records actual local results and exact-revision hosted CI and CodeQL results, including any unresolved gate
