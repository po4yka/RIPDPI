## Purpose

Define deterministic and fail-closed relationships between autonomous task
portfolios without introducing a shared database or duplicated canonical state.

## ADDED Requirements

### Requirement: REQ-TASK-FED-001 — Qualified task identity is unambiguous

The system MUST identify an external task as `owner/repository#TASK-ID` and
MUST keep local ID allocation independent in each repository.

#### Scenario: Equal local IDs exist in two repositories

- **GIVEN** two repositories contain the same local task ID
- **WHEN** their exports are combined
- **THEN** the project-qualified graph nodes MUST remain distinct

### Requirement: REQ-TASK-FED-002 — One-sided relations produce a complete graph

The system MUST accept qualified values in `parent`, `blocked_by`, and
`related_tasks` and MUST derive reverse parent and blocker edges.

#### Scenario: A deploy task depends on a RIPDPI task

- **GIVEN** only the deploy record declares the qualified blocker
- **WHEN** federation graph output is generated
- **THEN** both the forward blocker and derived reverse `blocks` edge MUST appear

### Requirement: REQ-TASK-FED-003 — External readiness fails closed

The system MUST exclude a task from the ready frontier when an external blocker
is missing, unavailable, active but incomplete, or terminally dropped.

#### Scenario: Peer data is unavailable

- **GIVEN** a task has a qualified external blocker
- **WHEN** readiness is evaluated without a matching peer checkout
- **THEN** the task MUST remain non-ready with an unresolved-external reason

### Requirement: REQ-TASK-FED-004 — Terminal history survives purge

Strict federation MUST resolve a purged external task from its validated Git
terminal record when full peer history is available.

#### Scenario: Completed blocker was purged

- **GIVEN** the peer task reached a valid `done` terminal commit and was deleted
- **WHEN** strict federation evaluates a consumer from a full-history checkout
- **THEN** the external blocker MUST be considered satisfied

### Requirement: REQ-TASK-FED-005 — Invalid combined graphs are rejected

Strict federation MUST reject unknown projects, missing task IDs, incompatible
contract versions, self references, cross-repository cycles, and a dropped task
used as a satisfied blocker.

#### Scenario: A blocker cycle crosses repository boundaries

- **GIVEN** each local portfolio is acyclic on its own
- **WHEN** their qualified blocker edges form a combined cycle
- **THEN** federation validation MUST fail and report the qualified cycle

### Requirement: REQ-TASK-FED-006 — Exports are reproducible and privacy-bounded

The JSON export MUST include contract version, project, revision, task path,
state, progress, relationships, and OpenSpec identity, and MUST omit task bodies
and detailed evidence.

#### Scenario: Export the same checked-out revision twice

- **GIVEN** portfolio files are unchanged
- **WHEN** `taskctl export --json` runs twice
- **THEN** normalized JSON output MUST be byte-identical
