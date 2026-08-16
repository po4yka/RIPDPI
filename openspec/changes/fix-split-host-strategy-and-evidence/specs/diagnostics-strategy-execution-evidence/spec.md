## Purpose

Provide privacy-safe proof of what desynchronization plan a diagnostic candidate
actually executed, independently of whether the remote endpoint later replied.

## ADDED Requirements

### Requirement: REQ-STRATEGY-EVIDENCE-001 — Exact configured and effective plan

Each attempted strategy candidate MUST record both its configured plan shape and
the effective plan selected at execution time, including the strategy family,
marker base and bounded delta, resolved offset availability, planned action
count, route-feature categories, and observation path.

#### Scenario: Current and catalog split candidates differ

- **GIVEN** the current strategy is `split(host+1)` and the catalog candidate is
  `split(host+2)`
- **WHEN** both candidates are planned
- **THEN** their evidence identifies the distinct marker deltas without exposing
  the host, SNI, address, or payload

#### Scenario: Effective plan differs from configured plan

- **GIVEN** capability, activation, adaptive, rotation, relay, WARP, WebSocket,
  or routing policy changes the selected execution path
- **WHEN** the candidate is attempted
- **THEN** the evidence records the configured and effective categories
  separately and the candidate cannot be described as the configured plan alone

### Requirement: REQ-STRATEGY-EVIDENCE-002 — Typed execution disposition

Each candidate generation MUST produce a typed disposition that distinguishes
`APPLIED`, `ACTIVATION_SKIPPED`, `PLAN_FAILED_PLAIN_FALLBACK`,
`EXECUTION_FAILED`, `RUNTIME_FAILED`, and `UNVERIFIED_EXECUTION`.

#### Scenario: Split is applied but the endpoint does not reply

- **GIVEN** the split plan resolves and its real writes complete
- **WHEN** the connection receives no valid HTTP response or TLS ServerHello
- **THEN** the receipt remains `APPLIED` and records the later response stage
  failure separately

#### Scenario: Planner falls back to a plain write

- **GIVEN** marker resolution or planning fails
- **WHEN** the runtime sends the original payload without desync actions
- **THEN** the receipt is `PLAN_FAILED_PLAIN_FALLBACK` and the attempt MUST NOT
  count as an evaluated split strategy

#### Scenario: Activation does not select the group

- **GIVEN** the configured activation or host filter does not match
- **WHEN** the first outbound payload is processed
- **THEN** the receipt is `ACTIVATION_SKIPPED` rather than a network-path failure

### Requirement: REQ-STRATEGY-EVIDENCE-003 — Bounded execution counters

An applied receipt MUST expose bounded counts for planned steps, attempted
actions, completed actions, committed real writes, completed await boundaries,
and committed payload bytes, plus the terminal socket-write and response stage.

#### Scenario: Host split executes

- **GIVEN** a production candidate runtime executes `split(host+1)`
- **WHEN** both segments are committed and the inter-segment await completes
- **THEN** the receipt proves two real writes, one completed await boundary, and
  a resolved `host+1` offset before reporting the endpoint outcome

### Requirement: REQ-STRATEGY-EVIDENCE-004 — Generation-safe terminal receipt

Candidate lifecycle shutdown MUST return a typed terminal receipt containing
cleanup, runtime terminal status, and the execution evidence for the same
candidate generation.

#### Scenario: Worker fails after readiness

- **GIVEN** a candidate runtime reports ready and then its worker returns an
  error or panics
- **WHEN** the attempt is finalized
- **THEN** the candidate is classified as `RUNTIME_FAILED`, not as a failed
  network strategy

#### Scenario: Late receipt after cancellation

- **GIVEN** candidate generation N is cancelled and generation N+1 starts
- **WHEN** a late receipt from N arrives
- **THEN** it cannot update or complete the evidence for N+1

### Requirement: REQ-STRATEGY-EVIDENCE-005 — Candidate path isolation

A catalog candidate MUST either clear unrelated route-stack features from its
base configuration or record every retained feature category that can affect
the effective path.

#### Scenario: Current profile has an upstream relay

- **GIVEN** the user profile enables a relay, WARP, WebSocket tunnel, rotation,
  adaptive policy, destination routing, or activation filter
- **WHEN** a canonical split candidate is built
- **THEN** that feature is isolated from the candidate or explicitly evidenced
  as part of the effective path

### Requirement: REQ-STRATEGY-EVIDENCE-006 — Privacy-safe archive contract

Serialized strategy execution evidence MUST use allowlisted enums and bounded
numeric fields and MUST NOT include domains, SNI, IP addresses, interface names,
payload bytes, credentials, raw configuration JSON, or stable network IDs.

#### Scenario: Hostile runtime strings are supplied

- **GIVEN** runtime or stored evidence contains destination, credential, path,
  or arbitrary string sentinels
- **WHEN** the diagnostic archive is rendered
- **THEN** every ZIP entry excludes those values while preserving the typed
  evidence needed to distinguish applied, skipped, fallback, and failed states
