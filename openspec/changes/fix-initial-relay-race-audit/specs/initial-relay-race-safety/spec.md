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

### Requirement: REQ-RACE-FRESH-EVIDENCE — Keep initial evidence within its runtime race

A new initial race MUST NOT use successful initial-probe evidence from a stopped runtime to mark its current failed probe healthy. Recent successful evidence from the same live runtime MUST retain its existing meaning.

#### Scenario: The same profile starts again

- **WHEN** one race succeeds, stops, and a new race for the same profile and scope fails its application probe
- **THEN** the new result remains verification-inconclusive instead of reusing the stopped runtime success

### Requirement: REQ-RACE-RESOLVED-IDENTITY — Keep the captured candidate identity

A race MUST reject a resolved kind or profile identity that differs from its captured candidate before it creates the runtime. Ordinary non-race profile resolution MUST retain its current persisted-profile behavior.

#### Scenario: A profile changes during candidate resolution

- **WHEN** the resolver returns a different kind or profile ID for the captured candidate
- **THEN** no runtime starts for that attempt and no selected result is published for it
