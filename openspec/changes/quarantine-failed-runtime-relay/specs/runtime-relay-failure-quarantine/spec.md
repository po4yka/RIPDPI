## Purpose

Prevent a relay whose runtime egress has been actively confirmed as unavailable from being selected repeatedly on the same network while preserving bounded recovery and network isolation.

## ADDED Requirements

### Requirement: REQ-RUNTIME-RELAY-NEGATIVE-EVIDENCE — Persist confirmed capability failure

The implementation MUST record a failed active relay egress probe as negative evidence for the active profile, relay kind, network scope, and capability proof actually tested.

#### Scenario: TCP confirmation fails

- **GIVEN** an active relay profile on a known network scope and a TCP-only egress requirement
- **WHEN** the active capability probe returns a failed TCP result
- **THEN** the profile is recorded as failed for the `tcp_only` proof on that network scope

#### Scenario: Candidate provides fewer capabilities than requested

- **GIVEN** the session requests TCP and UDP but the active relay supports only TCP
- **WHEN** the active probe tests TCP and fails
- **THEN** negative evidence is recorded for `tcp_only`, not for the untested `tcp_udp` proof

### Requirement: REQ-RUNTIME-RELAY-COOLDOWN — Exclude confirmed failures for a bounded interval

The implementation MUST exclude matching negative evidence from later candidate construction until the existing cooldown expires.

#### Scenario: A later initial race is built on the same network

- **GIVEN** a relay profile has a non-expired confirmed failure for the current network and proof
- **WHEN** the application constructs relay candidates again
- **THEN** that profile is omitted while compatible alternatives remain eligible

#### Scenario: The cooldown expires

- **GIVEN** a relay profile was quarantined by a confirmed failure
- **WHEN** the configured cooldown has elapsed
- **THEN** the profile becomes eligible for a new active verification attempt

### Requirement: REQ-RUNTIME-RELAY-ISOLATION — Preserve recovery and privacy boundaries

The implementation MUST keep failures network-scoped, MUST NOT quarantine a profile after a successful confirmation, and MUST persist no endpoint or credential in the negative cache.

#### Scenario: The same profile is evaluated on another network

- **GIVEN** a profile is cooling down on one network scope
- **WHEN** candidates are constructed for a different network scope
- **THEN** the profile remains eligible on the other network

#### Scenario: Active confirmation succeeds

- **GIVEN** passive runtime telemetry reports relay failure
- **WHEN** the active capability probe succeeds
- **THEN** no negative evidence is recorded and failover debounce is cleared
