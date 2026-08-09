## Purpose

Define the observable contract for repository-native task management and OpenSpec integration.

## ADDED Requirements

### Requirement: REQ-CIC-1786264762917931-001 — Tasking dependencies and agent assets are reproducible

The repository MUST pin mdtask 0.1.17 and OpenSpec 1.8.0 locally, disable OpenSpec telemetry, and verify adapted generated assets by version and checksum.

#### Scenario: Install in a clean copy

- **WHEN** a contributor runs `npm ci --prefix tools/tasking --ignore-scripts` without global tasking tools
- **THEN** `taskctl` MUST use only the pinned local binaries and generated assets MUST match the lock manifest

### Requirement: REQ-CIC-1786264762917931-002 — Portfolio lifecycle is fail-closed

The portfolio MUST enforce the strict schema, globally unique numeric suffixes, canonical blockers, derived reverse edges, ready frontier, OpenSpec risk rules, terminal receipts, and deletion history.

#### Scenario: Attempt a lifecycle bypass

- **WHEN** a task is manually closed, archived, dropped, or deleted outside the wrapper contract
- **THEN** local and CI validation MUST reject the repository state

### Requirement: REQ-CIC-1786264762917931-003 — Open work is migrated without semantic loss

All 48 open legacy records MUST retain their status, priority, owner, relationships, body acceptance criteria, and execution state; partial legacy criteria MUST remain open rather than being reported complete.

#### Scenario: Compare the migration

- **WHEN** the legacy and migrated portfolios are compared
- **THEN** task counts, status distribution, parent edges, and acceptance-state meaning MUST match

### Requirement: REQ-CIC-1786264762917931-004 — OpenSpec changes carry requirements and evidence

Every required change MUST use the RIPDPI schema, stable requirement IDs, Given/When/Then scenarios, mdtask execution steps, and requirement-to-evidence mappings.

#### Scenario: Validate every active change

- **WHEN** OpenSpec strict validation runs across the repository
- **THEN** every active change and the custom schema MUST pass without skipping validation

### Requirement: REQ-CIC-1786264762917931-005 — Repository automation enforces the contract

CI, Lefthook, just recipes, PR metadata, issue-entry configuration, and security reporting guidance MUST route task operations through `taskctl` and preserve private vulnerability reporting.

#### Scenario: Change task or specification state

- **WHEN** task, OpenSpec, generated skill, or lifecycle files change
- **THEN** the unconditional task-contract gate MUST validate them before integration

### Requirement: REQ-CIC-1786264762917931-006 — Parallel and clean-copy workflows are tested

The test suite MUST cover independent worktree creation with an allocator collision, lifecycle success and failure paths, and installation in a copy without pre-existing `node_modules`.

#### Scenario: Exercise isolated worktrees

- **WHEN** two worktrees allocate from the same millisecond and initial random value
- **THEN** the shared reservation MUST produce unique IDs and the merged portfolio MUST validate

### Requirement: REQ-CIC-1786264762917931-007 — External repository state is verified before completion

The integration MUST remain open until owner/legal approval is recorded, the review branch passes required remote CI, public GitHub Issues are disabled, and Private Vulnerability Reporting is confirmed enabled.

#### Scenario: Declare the integration complete

- **WHEN** the portfolio task is considered for `review` or terminal closure
- **THEN** each external requirement MUST have observed evidence tied to the exact integration SHA
