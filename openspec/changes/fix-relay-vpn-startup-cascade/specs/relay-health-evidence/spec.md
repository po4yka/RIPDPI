## Purpose

Define a single evidence-based relay health contract that prevents target-specific probe failures from cascading into destructive VPN restarts while preserving bounded fail-closed startup, exact cleanup, and privacy-safe diagnostics.

## ADDED Requirements

### Requirement: REQ-RLY-1786707070050078-001 — Startup and runtime share one relay health decision contract

The implementation MUST evaluate initial relay readiness and steady-state failover through the same typed observation and decision contract, using profile-derived probe targets instead of a separate runtime-only public endpoint.

#### Scenario: Imported profile supplies the probe target

- **WHEN** an imported relay profile contains an application-level health-check target
- **THEN** startup and runtime supervision use the same normalized target identity and capability requirements

#### Scenario: Imported profile has no usable target

- **WHEN** no privacy-safe application target is available from the active profile
- **THEN** synthetic target verification is reported as inconclusive without adding or contacting a new built-in public host

### Requirement: REQ-RLY-1786707070050078-002 — Positive data-plane evidence prevents false relay failure

The implementation MUST classify a relay with recent successful streams or advancing data-plane counters as healthy and clear pending negative state, even if a synthetic DNS or HTTP target check fails after the relay handshake.

#### Scenario: Traffic succeeds while the probe target times out

- **WHEN** the active relay has recent positive data-plane evidence and its synthetic target returns a DNS or HTTP timeout
- **THEN** the decision is healthy or inconclusive, no candidate switch occurs, and no cooldown is recorded

### Requirement: REQ-RLY-1786707070050078-003 — Relay failure requires relay-scoped evidence

The implementation MUST distinguish target-specific failures from relay-scoped transport, REALITY TLS, VLESS authentication, and VLESS request failures, and MUST require two non-permanent relay-scoped observations separated by the configured debounce unless a permanent authentication or configuration rejection is observed.

#### Scenario: Target-specific timeout is not relay proof

- **WHEN** SOCKS or relay establishment succeeds and only the downstream DNS or HTTP target fails
- **THEN** the observation is inconclusive and cannot by itself switch or quarantine the relay

#### Scenario: Repeated relay-stage failure confirms the candidate

- **WHEN** two relay-scoped failures occur at least 20 seconds apart with no intervening positive evidence
- **THEN** the relay tuple becomes confirmed failed exactly once

#### Scenario: Permanent authentication rejection

- **WHEN** the relay returns an observed permanent authentication or configuration rejection
- **THEN** the relay tuple becomes confirmed failed without a redundant second network attempt

### Requirement: REQ-RLY-1786707070050078-004 — Probe, retry, and cooldown behavior is bounded

The implementation MUST allow at most one active probe per relay tuple, enforce at least 20 seconds between non-permanent probes, attempt a candidate no more than twice in one startup generation, and apply the existing 15-minute cooldown only to confirmed failures.

#### Scenario: Concurrent telemetry updates request confirmation

- **WHEN** multiple telemetry samples report the same pending relay failure concurrently
- **THEN** one probe runs and all callers observe its single decision

#### Scenario: Canonical network scope is unavailable

- **WHEN** a confirmed failure has no privacy-safe persistent network scope
- **THEN** cooldown is kept only for the current in-memory underlay generation and no unidentified persistent key is written

#### Scenario: Relay later succeeds

- **WHEN** positive relay evidence arrives after a negative observation or cooldown
- **THEN** the pending failure latch and matching negative cooldown are cleared

### Requirement: REQ-RLY-1786707070050078-005 — Candidate transitions preserve lifecycle isolation

The implementation MUST finish and await cleanup of a failed or losing relay session before starting the next candidate, and MUST apply TCP-only capability requirements to the selected session without enabling UDP ASSOCIATE.

#### Scenario: Startup moves to the next candidate

- **WHEN** a candidate is confirmed failed or loses the initial race
- **THEN** its runtime, jobs, sockets, and listeners are stopped before the successor enters its critical startup section

#### Scenario: TCP-only fallback is selected

- **WHEN** the effective plan requires TCP connect but not UDP associate
- **THEN** both the relay active probe and session-local proxy configuration disable UDP ASSOCIATE

#### Scenario: UDP target is absent

- **WHEN** UDP verification is requested without an explicit safe UDP target
- **THEN** the missing target is reported as inconclusive and is not classified as relay failure

### Requirement: REQ-RLY-1786707070050078-006 — Runtime readiness and verified VPN egress remain distinct

The implementation MUST represent local processes ready, VPN checking, VPN validated, verification inconclusive, and all candidates exhausted as distinct observable states.

#### Scenario: Local listeners are ready but egress is unproven

- **WHEN** relay, proxy, and tunnel processes are running without positive egress evidence
- **THEN** the app reports VPN checking rather than VPN working

#### Scenario: Candidate exhaustion

- **WHEN** every eligible candidate reaches confirmed failure within the bounded startup generation
- **THEN** the app fails closed once, reports candidates exhausted, and leaves no relay or tunnel process behind

### Requirement: REQ-RLY-1786707070050078-007 — Relay health decisions are exported with privacy-safe provenance

The implementation MUST export the decision attempt, opaque profile identity, transport, observed failure stage, target category, positive-evidence watermark, decision, cooldown scope, and cleanup receipt without raw endpoints, network identifiers, or credentials.

#### Scenario: Diagnostic archive contains a relay decision

- **WHEN** relay health evidence is persisted and exported
- **THEN** the archive preserves ordered decision provenance and completeness counters while redacting raw addresses, SSIDs, BSSIDs, UUIDs, and credentials

#### Scenario: Evidence is incomplete

- **WHEN** a decision lacks a required correlation or stage field
- **THEN** the archive marks that provenance unavailable instead of fabricating a runtime, connection, target, or cause

### Requirement: REQ-RLY-1786707070050078-008 — Pixel 7 acceptance uses the exact integrated artifact and dad-phone bundle

The implementation MUST pass the controlled Pixel 7 startup/failover matrix using the exact integrated simple-flavor artifact and owner-controlled `dad-phone` profile, restore the original VPN state, and keep total intentional disruption within 15 minutes.

#### Scenario: Physical acceptance matrix completes

- **WHEN** normal starts, target-only failure, actual primary failure, total candidate failure, and recovery are exercised on the connected Pixel 7
- **THEN** evidence records Android validation, TUN routing, real DNS/HTTPS traffic, active transport, bounded probes and restarts, cleanup isolation, exact failure stages, and final restoration without exposing profile secrets
