# parallel-active-probe-race-initial-transport-selection Specification

## Purpose

Define the core one-or-two-candidate relay selection API and the connected Simple single-profile preflight. The core race is not a production policy binding. Neither a listener nor a transport-class label proves verified VPN egress.

## Requirements

### Requirement: REQ-TRN-1786264762917886-001 — A core race can select healthy Hysteria2 while Reality stalls

When a caller supplies compatible Reality and Hysteria2 candidates, the core runner MUST allow a healthy Hysteria2 application exchange to win without waiting for the stalled Reality probe. The Simple production policy supplies only its configured profile.

#### Scenario: A core race can select healthy Hysteria2 while Reality stalls

- **GIVEN** A caller supplies two candidates and the Reality application exchange stalls
- **WHEN** Hysteria2 completes its probe first
- **THEN** The core runner selects Hysteria2 and closes the discarded candidate

### Requirement: REQ-TRN-1786264762917886-002 — A core race can select healthy Reality when UDP is blocked

When a caller supplies two candidates, the core runner MUST allow healthy Reality to win when the Hysteria2 carrier cannot complete its application exchange. A UDP carrier MUST NOT be treated as proof of user UDP capability.

#### Scenario: A core race can select healthy Reality when UDP is blocked

- **GIVEN** A caller supplies compatible Reality and Hysteria2 candidates
- **WHEN** The Hysteria2 UDP carrier is blocked and Reality passes its TCP application probe
- **THEN** The core runner selects Reality

### Requirement: REQ-TRN-1786264762917886-003 — Simple preflight preserves explicit verification outcomes

For an eligible seeded profile without a captured selector selection, the Simple native VPN path MUST complete the configured profile preflight before it starts the proxy and TUN. A target-specific failure or an unavailable target MAY retain a live relay with an explicit inconclusive result. AWG readiness MUST require a successful active exchange. Listener readiness alone MUST NOT be reported as verified egress.

#### Scenario: Simple preflight preserves explicit verification outcomes

- **GIVEN** A configured seeded relay has a live listener
- **WHEN** Its application target fails without relay-scoped rejection evidence
- **THEN** The relay remains explicitly verification-inconclusive rather than being reported as probe-confirmed

### Requirement: REQ-TRN-1786264762917886-004 — Race selection requires live candidates and completed loser cleanup

The core runner MUST select the first completed successful probe from a live candidate. If only inconclusive attempts remain, it MUST choose a live provisional candidate in plan order. It MUST finish loser cleanup before successful selection and MUST retain failed cleanup for shutdown retry without an unexpected loser-exit event.

#### Scenario: Race selection requires live candidates and completed loser cleanup

- **GIVEN** The first provisional candidate exits while another probe is pending
- **WHEN** The other candidate finishes inconclusively and remains live
- **THEN** The live candidate is selected; failed loser cleanup prevents successful publication

### Requirement: REQ-TRN-1786264762917886-005 — Cached fallback eligibility belongs to the supplying caller

The core API MAY accept a caller-validated cached fallback identity. It MUST use only an eligible candidate in the current plan whose runtime remains live. The connected Simple policy MUST NOT claim a winner cache: it supplies one configured candidate and no cached fallback identity.

#### Scenario: Cached fallback eligibility belongs to the supplying caller

- **GIVEN** The current Simple policy builds its plan
- **WHEN** The configured seeded relay is eligible
- **THEN** The plan contains that profile alone and no cached fallback profile ID

### Requirement: REQ-TRN-1786264762917886-006 — Handover and transport replacement preflight the configured profile

A native VPN handover or transport replacement MUST preflight its configured seeded profile through the connected Simple policy when that profile is eligible and no captured selector selection suppresses the preflight. It MUST NOT start an unbound two-candidate product race. Startup MUST preserve a physical network change until handover can act on the connected session.

#### Scenario: Handover and transport replacement preflight the configured profile

- **GIVEN** Cold startup resolved policy on network A
- **WHEN** Network B replaces A during the suspended runtime start
- **THEN** The first connected session observes the network change and uses the existing handover restart path

### Requirement: REQ-TRN-1786264762917886-007 — Integrated-tree gate evidence is complete

The change MUST pass its focused Rust, Kotlin, simple-flavor, architecture, static-analysis, and controlled relay-lab gates on the integrated tree.

#### Scenario: Verify the integrated gate set

- **WHEN** the change is rebased onto the target integration SHA and the named gates run
- **THEN** every required gate MUST have an observed passing result or an explicitly blocked evidence state that keeps the task open
