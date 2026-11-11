## Purpose

Use local observations to tune DNS ranking and select repeat diagnostic targets
with a fixed CPU budget and no new outbound traffic.

## ADDED Requirements

### Requirement: REQ-DNS-DE — Validate learned DNS weights

The resolver MUST fit bounded DNS score weights with deterministic Differential
Evolution on pre-outcome observations. It MUST retain the default model when
data is sparse, stale, or has no improvement on a later validation partition.
Calibration MUST run outside the registry lock and have a fixed evaluation cap.

#### Scenario: A model improves validation loss

- **WHEN** a network scope has enough recent observations and a candidate improves
  on both default and current weights on the later validation partition
- **THEN** subsequent endpoint ranking uses the candidate in that scope only

#### Scenario: Safety remains authoritative

- **WHEN** a fast endpoint is quarantined or observations are insufficient
- **THEN** quarantine remains ahead of score sorting and the default fallback remains available

### Requirement: REQ-DIAG-SA — Select a compact repeat target set

Automatic repeat diagnostics MUST use bounded, deterministic Simulated Annealing
to choose targets from recent local evidence within existing target limits.
Control, unknown, failed, and special-contract targets MUST be retained whenever
compaction is feasible. Infeasible or stale evidence MUST preserve existing planning.
Full manual scans MUST retain their existing target coverage.

#### Scenario: Equivalent healthy targets exceed the budget

- **WHEN** recent complete evidence contains interchangeable healthy targets
- **THEN** selection preserves evidence coverage and prefers a lower measured cost

#### Scenario: A target has unique or missing evidence

- **WHEN** removing a target loses a required signal or the mandatory set exceeds the budget
- **THEN** selection retains the target or returns the original planner fallback

### Requirement: REQ-LOCAL-BOUNDS — Bound and isolate learning state

Both algorithms MUST use local bounded memory, deterministic seeds, finite scores,
and fixed search limits. Learning MUST NOT open sockets, log raw identifiers,
change schemas, or add third-party optimization dependencies.

#### Scenario: The network changes or the process restarts

- **WHEN** observations belong to another scope or no local learning state exists
- **THEN** the current network starts with the existing defaults
