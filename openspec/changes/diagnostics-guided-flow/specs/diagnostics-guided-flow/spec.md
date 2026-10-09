## Purpose

Make local diagnostics understandable, cancellable, and actionable while preserving the limits of measured evidence.

## ADDED Requirements

### Requirement: REQ-UX-CONTROLS — Stable run controls

The app MUST expose quick analysis and a stop action for the complete Home analysis, and MUST preserve the active scan profile identity.

#### Scenario: Stop a composite run

- **WHEN** the user stops an active Home analysis
- **THEN** the complete run stops and no later stage starts.

#### Scenario: Select a profile during a run

- **WHEN** a scan is active
- **THEN** a profile change cannot alter the running scan or its progress labels.

### Requirement: REQ-UX-OWNERSHIP — Full-run admission ownership

The diagnostics runtime MUST hold one Home run lease through teardown and MUST reject unrelated manual or automatic scan admission and profile mutation while the lease is held.

#### Scenario: Admit a scan between composite stages

- **WHEN** the Home lease is active and no native session is running
- **THEN** only a stage with the same owner ID can start; unrelated scans and profile mutation are rejected.

#### Scenario: Release a stopped Home run

- **WHEN** cancellation teardown completes
- **THEN** the lease is released and new manual scans can start.

### Requirement: REQ-UX-ACTIONS — Honest next actions

The app MUST use the saved persona for disclosure and MUST describe each recommendation action according to its actual effect.

#### Scenario: Review a recommended change

- **WHEN** the user selects a recommendation
- **THEN** the app shows the concrete configuration and measurement scope before any connection change.

#### Scenario: Recheck a result

- **WHEN** the user requests a recheck
- **THEN** the app starts a measured check and keeps changed-network evidence distinct.

### Requirement: REQ-UX-EVIDENCE — Readable and complete results

The app MUST show run controls before catalogs, concise result groups before attempts, and preserve transfer and stage evidence in explicit local copy operations.

#### Scenario: Inspect a matrix result

- **WHEN** results are available
- **THEN** the summary is visible and the target attempts and catalog can be expanded.

#### Scenario: Copy partial evidence

- **WHEN** a partial transfer or connection result is copied
- **THEN** measured values, reason, scope, and unknown states are retained without a fabricated cause.

### Requirement: REQ-UX-CAUSE — Bounded classification

The app MUST NOT claim an IP or SNI blocking mechanism from one transport failure without comparative evidence.

#### Scenario: A connection is refused or reset

- **WHEN** Blockcheck has only one failed connection observation
- **THEN** it reports the observation with an unknown mechanism.
