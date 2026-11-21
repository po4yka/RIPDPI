## ADDED Requirements

### Requirement: REQ-DGN-1795238144093160-001 — Direct-path learning requires current origin

The learner MUST accept a proxy observation only while its producing runtime and physical network generation remain current.

#### Scenario: The network changes before consumption

- **WHEN** a proxy reads an observation from its queue and the physical network changes before learning
- **THEN** the result does not change remembered direct-path policy

### Requirement: REQ-DGN-1795238144093160-002 — A revoked VPN route cannot authorize a diagnosis

The app MUST retain measured facts from an in-path run with a revoked VPN route and mark route-dependent conclusions as inconclusive.

#### Scenario: The runtime changes on the same network

- **WHEN** the VPN route lease is revoked during a scan on unchanged Wi-Fi
- **THEN** the partial report cannot establish an in-path comparison or authorize policy updates

### Requirement: REQ-DGN-1795238144093160-003 — Interference claims require related evidence

The classifier MUST keep SNI observations on different targets separate.

#### Scenario: Different targets produce the observations

- **WHEN** the control succeeds on one target and another target fails
- **THEN** the app keeps the separate facts and does not infer a shared SNI cause

### Requirement: REQ-DGN-1795238144093160-004 — Automatic DNS updates require verified recovery

The app MUST require verified recovery for the same DNS question on the current path before it applies or remembers a resolver change.

#### Scenario: The same query recovers

- **WHEN** a completed scan verifies recovery for the affected DNS query through a trusted resolver on the current path
- **THEN** the app can apply the eligible DNS override and remember the verified path
