## Purpose

Measure selective HTTPS availability across dated independent target cohorts without claiming an unobserved provider cause.

## ADDED Requirements

### Requirement: REQ-MATRIX-CATALOG — Dated manual target cohorts

The app MUST offer a manual-only selective availability profile with declared_available, domestic, global and optional user cohorts. Every bundled target MUST include its provenance and infrastructure group; declared membership MUST not be presented as measured availability.

#### Scenario: Dated manual target cohorts

- **WHEN** the user selects the matrix profile
- **THEN** the app shows the target groups and accepts at most four validated optional HTTPS host targets.

### Requirement: REQ-MATRIX-PROBES — Bounded complete-response measurements

The engine MUST record DNS, TCP, verified TLS, HTTP and body completion for each attempt. It MUST enforce target, repetition, time and byte limits and honor cancellation. Partial or capped bodies MUST not be reported as complete responses.

#### Scenario: Bounded complete-response measurements

- **WHEN** a server sends headers then stalls or exceeds the body limit
- **THEN** the result records the observed stages and incomplete body, without a reachable result.

### Requirement: REQ-MATRIX-EVIDENCE — Conservative cohort conclusions

The matrix MUST distinguish available, selective, unavailable, mixed and inconclusive observations. Selective results MUST require repeated successes in independent declared targets and repeated transport failures in independent comparison targets. Missing attempts, invalid controls, server errors and network scope changes MUST not prove an allowlist or provider cause.

#### Scenario: Conservative cohort conclusions

- **WHEN** the control endpoint fails, the network changes, or the run stops early
- **THEN** the app retains observations and explains the uncertainty without a provider-cause verdict.

### Requirement: REQ-MATRIX-UI — Readable matrix and user input

The app MUST render target groups, per-stage states, repeat counts, coverage and measurement limits in current and historical scan details. Invalid user hosts MUST prevent starting a misleading scan.

#### Scenario: Readable matrix and user input

- **WHEN** a matrix report is opened from history
- **THEN** the same target and stage evidence is visible with localized labels.

### Requirement: REQ-MATRIX-STORAGE — Local bounded evidence and export

The app MUST persist matrix facts using existing scan storage and include them in redacted human-readable and structured exports. It MUST not retain response bodies, credentials, cookies or raw user network identifiers.

#### Scenario: Local bounded evidence and export

- **WHEN** a matrix scan is exported
- **THEN** target provenance, stage outcomes and completion limits survive while sensitive values follow existing redaction policy.
