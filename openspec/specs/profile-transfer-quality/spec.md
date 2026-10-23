# profile-transfer-quality Specification

## Purpose
Select an improved encrypted profile using bounded, authenticated transfer quality
without replacing the existing selector or confusing missing evidence with failure.

## Requirements

### Requirement: REQ-QUALITY-MEASURE — Verified candidate transfer facts

The client MUST verify candidate-owned payload transfers and report body goodput and authenticated HTTP transaction latency with honest overlap evidence.

#### Scenario: Required measurement contract

- **WHEN** this capability is evaluated
- **THEN** The client MUST measure through the candidate's owned encrypted runtime and verify the configured GET body, EOF and SHA-256 before reporting useful-byte goodput. Goodput MUST use the body-read interval, excluding runtime startup and TLS setup. Baseline and loaded latency MUST be authenticated HTTP HEAD transaction timings against that same object, with status 200 and exact Content-Length. A loaded HEAD MUST begin and finish during the actual GET body interval to qualify. Absent or partial observations MUST remain unknown, without zero-valued success or an MTU claim.

#### Scenario: Handshake speed does not hide slow transfer

- **WHEN** one real candidate connects sooner but transfers the verified body more slowly
- **THEN** its goodput is lower regardless of its initial connection time

#### Scenario: Transfer completes before the loaded control

- **WHEN** the exact GET body finishes before the concurrent HEAD completes
- **THEN** verified goodput remains available and loaded latency is unavailable
- **AND** the sample does not authorize a quality switch or become a health failure

#### Scenario: Invalid authenticated payload

- **WHEN** the body is truncated, compressed, redirected, oversized or has the wrong digest
- **THEN** it does not supply goodput or authorize selection

### Requirement: REQ-QUALITY-BUDGET — Durable bounded measurement work

Quality measurement MUST require explicit opt-in and enforce durable application-wide body budgets before I/O, including conservative clock and persistence failure handling.

#### Scenario: Required measurement contract

- **WHEN** this capability is evaluated
- **THEN** Quality measurement MUST require explicit policy opt-in and reserve application body allowances before I/O. A pass MUST cover at most the active member and one reserve, with at most two origins each and one baseline HEAD, GET and loaded HEAD per origin. The reserved allowance MUST include one overrun-detection byte per GET and MUST NOT be refunded after failure, cancellation or incomplete measurement. An app-wide durable rolling ledger MUST enforce pass/window limits across groups, process restarts, policy refreshes and network changes. Clock ambiguity, corrupt state, exhausted capacity or persistence failure MUST refuse measurement without incrementing health failures. Body accounting MUST NOT be described as IP-wire bytes.

#### Scenario: Failed passes cannot refill the budget

- **WHEN** a reserved pass fails, the process restarts and the subscription is reimported
- **THEN** its charge remains in the shared rolling window and can block new I/O

#### Scenario: Network change during a pass

- **WHEN** the underlay changes after reservation
- **THEN** stale observations cannot select a profile and the charge is retained

### Requirement: REQ-QUALITY-SELECT — Stable improvements in the existing selector

The existing selector MUST require sustained comparable quality improvement and preserve cooldown, scope fences and verified health recovery.

#### Scenario: Required measurement contract

- **WHEN** this capability is evaluated
- **THEN** The existing selector MUST compare the active member and one rotating reserve even while the active member is healthy when quality is enabled. A quality switch MUST require complete comparable observations, configured consecutive wins and the existing cooldown. Compared observations MUST use the same admitted control index, exact object digest and policy snapshot; another origin may prove health but MUST NOT supply a cross-origin quality comparison. A winner MUST either improve goodput by the configured margin without exceeding the allowed loaded-latency increase, or reduce positive loaded latency by that margin without reducing goodput. Manual selection, policy and network-generation fences MUST retain authority. Existing health fallback MUST remain available from actual payload outcomes; unknown quality is not a failure.

#### Scenario: Sustained faster transfer

- **WHEN** a reserve repeatedly exceeds the goodput margin within the loaded-latency allowance outside cooldown
- **THEN** it is selected through the existing guarded native reload path

#### Scenario: Lower delay with no throughput regression

- **WHEN** a reserve repeatedly reduces loaded latency by the margin without reducing verified goodput
- **THEN** the same selector may select it without creating another activation authority

#### Scenario: Noise, stale input or exhausted budget

- **WHEN** samples do not satisfy the margin, become stale, or a pass has no budget
- **THEN** the encrypted selection remains and no direct fallback is created

### Requirement: REQ-QUALITY-LIFETIME — Reuse native candidate ownership

Relay and Reality measurements MUST reuse their existing runtime owners,
configuration proofs, VPN protection and bounded cleanup. Cancellation and
pending cleanup MUST prevent overlapping replacement of owned handles. AWG MUST
remain outside the automatic relay-member pool; no hidden VPN switch is allowed.

#### Scenario: Reality candidate measurement

- **WHEN** a supported Reality member participates in an opted-in selector
- **THEN** the existing Xray candidate owner verifies real payload traffic and retires its runtime before publishing a result

### Requirement: REQ-QUALITY-PRESENT — Scoped useful evidence

Existing profile UI MUST expose verified goodput, baseline/loaded transaction
latency, incomplete reasons, selection decisions and budget state from a typed observable store.
Observations MUST be invalidated when their authority changes and MUST NOT contain
control URLs, credentials or raw network identifiers. Budget history remains intact.

#### Scenario: Incomplete measurement remains understandable

- **WHEN** a profile has verified goodput but no fully overlapping loaded control
- **THEN** UI presents the useful speed and an unavailable loaded value with its reason
