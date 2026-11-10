## Purpose

Keep initial relay selection tied to unique, live resources and preserve network changes during startup.

## ADDED Requirements

### Requirement: REQ-RACE-IDENTITY — Reject duplicate profile identities

The runner MUST reject duplicate or empty profile IDs before opening a candidate runtime.

#### Scenario: Two classes reuse one profile

- **WHEN** two candidates use one profile ID
- **THEN** the plan is rejected before any listener starts

### Requirement: REQ-RACE-LIVE — Select a live provisional candidate

The runner MUST select only a live runtime when it resolves provisional outcomes.

#### Scenario: The first provisional runtime exits

- **WHEN** the first provisional runtime exits while the second probe is pending
- **THEN** a live second provisional runtime is selected instead

### Requirement: REQ-RACE-CLEANUP — Require completed loser cleanup

The runner MUST refuse successful selection when loser cleanup remains pending and MUST retain ownership for retry.

#### Scenario: Loser stop fails

- **WHEN** a losing runtime cannot stop
- **THEN** no successful selection is published and later shutdown can retry all owned runtimes

### Requirement: REQ-RACE-NETWORK — Preserve cold-start physical network changes

The service MUST preserve physical network changes that occur after initial policy resolution and before handover monitoring can act.

#### Scenario: Network changes during cold startup

- **WHEN** startup captures network A and network B replaces it before startup finishes
- **THEN** the first connected session observes the A-to-B change and runs the current handover path

### Requirement: REQ-RACE-SCOPE — Describe the connected product scope

The specifications MUST distinguish the core one-or-two-candidate API from the Simple single-profile preflight. They MUST NOT promise an unbound winner cache or product race.

#### Scenario: Simple starts its selected profile

- **WHEN** the Simple flavor starts a seeded profile
- **THEN** its contract describes one configured candidate and explicit inconclusive verification without promising two concurrent product candidates
