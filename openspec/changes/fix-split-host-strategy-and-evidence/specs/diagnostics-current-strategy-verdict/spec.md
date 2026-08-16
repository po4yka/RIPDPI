## Purpose

Ensure that diagnostics describe only what was actually tested and never infer
the active strategy's success or failure from unrelated paths or candidates.

## ADDED Requirements

### Requirement: REQ-STRATEGY-VERDICT-001 — Exact current-strategy evidence

The current-strategy verdict MUST use only the `baseline_current` candidate for
the same immutable strategy snapshot and MUST require a complete applied
execution receipt with at least one attempted endpoint.

#### Scenario: Another matrix candidate fails

- **GIVEN** `baseline_current` has complete applied evidence and succeeds
- **WHEN** another strategy-matrix candidate fails
- **THEN** that failure does not invalidate the current strategy

#### Scenario: Baseline candidate is absent

- **GIVEN** a strategy report contains other candidates but no
  `baseline_current` attempt
- **WHEN** the verdict is computed
- **THEN** the result is `INCOMPLETE_EVIDENCE`, not success or failure

### Requirement: REQ-STRATEGY-VERDICT-002 — Observation-path separation

The verdict model MUST distinguish `EPHEMERAL_CANDIDATE_RAW_PATH` from
`ACTIVE_SERVICE_IN_PATH`; generic RAW_PATH connectivity MUST NOT validate or
invalidate the active VPN or proxy strategy.

#### Scenario: RAW_PATH connectivity succeeds

- **GIVEN** the active service is stopped for a raw connectivity stage
- **WHEN** all raw probes succeed
- **THEN** the active strategy remains unverified by that stage

#### Scenario: Ephemeral candidate fails

- **GIVEN** `baseline_current` runs in the local candidate runtime on RAW_PATH
- **WHEN** its execution is applied but endpoint probes fail
- **THEN** diagnostics may report that exact candidate-path attempt as
  ineffective but MUST NOT claim that the production VPN path failed

### Requirement: REQ-STRATEGY-VERDICT-003 — Completeness before evaluation

Outer or inner partial results, deadlines, plan-only reports, zero attempts,
launch failures, activation skips, plain fallbacks, runtime failures, and
missing execution receipts MUST result in `INCOMPLETE_EVIDENCE` or
`UNVERIFIED_EXECUTION` rather than an evaluated strategy failure.

#### Scenario: Inner candidate inventory is partial

- **GIVEN** the outer report completes normally but the strategy report has
  `PARTIAL_RESULTS`
- **WHEN** the current-strategy verdict is computed
- **THEN** it is `INCOMPLETE_EVIDENCE`

#### Scenario: Runtime launch fails before probes

- **GIVEN** a candidate declares targets but its runtime never starts
- **WHEN** its summary is rendered
- **THEN** the verdict reports launch failure and does not claim the strategy was
  evaluated against those targets

### Requirement: REQ-STRATEGY-VERDICT-004 — Layered failure attribution

Diagnostics MUST keep DNS integrity, candidate execution, socket write, HTTP,
TLS, QUIC, and active-service path evidence as separate axes and MUST identify
the last proven stage without promoting correlation to a root cause.

#### Scenario: DNS divergence and TLS failure coexist

- **GIVEN** one target has DNS divergence while other targets resolve and the
  applied current candidate receives no TLS ServerHello
- **WHEN** the report is generated
- **THEN** it reports both facts separately and does not label all current
  strategy failures as DNS tampering

### Requirement: REQ-STRATEGY-VERDICT-005 — User-facing wording matches proof

The UI and archive summary MUST distinguish `WORKING`, `INEFFECTIVE_ON_TESTED_CANDIDATE_PATH`,
`UNVERIFIED_EXECUTION`, `INCOMPLETE_EVIDENCE`, and `ACTIVE_PATH_UNVERIFIED`.

#### Scenario: Applied candidate receives no response

- **GIVEN** exact `split(host+1)` execution is proven on the ephemeral candidate
  path and all complete probes fail after successful writes
- **WHEN** the result is displayed
- **THEN** the wording is limited to ineffectiveness on that tested candidate
  path and does not claim a production VPN failure

#### Scenario: Active-service comparison is unavailable

- **GIVEN** the active VPN cannot be probed IN_PATH with authoritative route
  evidence
- **WHEN** diagnostics completes
- **THEN** the UI states `ACTIVE_PATH_UNVERIFIED` with a typed unavailable reason
