## Purpose

Give a device owner an offline, explicit way to prove receipt of a replacement
subscription and separately report a successful test of its exact new path.

## ADDED Requirements

### Requirement: REQ-MIGRATION-DELIVERY — Retain exact received identity

The client MUST validate versioned migration metadata and retain its delivery
identity and the SHA-256 of the exact delivered body bytes with the successfully
persisted subscription. It MUST reject malformed, ambiguous or unsupported
migration metadata as a whole and MUST NOT acknowledge incomplete profile writes.
An ordinary subscription without migration metadata MUST remain usable and MUST
NOT inherit a previous migration receipt.

#### Scenario: Valid replacement is received

- **WHEN** a valid migration subscription finishes persisting its declared profiles
- **THEN** the stored state identifies that delivery and exact received bytes
- **AND** the state reports received, without implying tested connectivity

#### Scenario: Refresh fails or changes delivery

- **WHEN** metadata is invalid, persistence fails, or refresh is interrupted
- **THEN** no new received or tested receipt is committed
- **WHEN** a different valid delivery replaces the stored subscription
- **THEN** prior tested evidence cannot authorize that new delivery

#### Scenario: AWG tag changes

- **WHEN** a valid migration maps a new AWG tag to a previous tag in the same subscription group
- **THEN** the new profile retains the local private key and local overrides while replacing delivered server fields
- **AND** the old row remains available and profiles in other groups cannot supply keys

### Requirement: REQ-MIGRATION-TARGET — Verify an exact declared new target

The client MUST provide an explicit user-triggered test of a declared migration
target using its exact imported configuration. A tested receipt MUST require a
completed bounded HTTP payload through the target's real authenticated transport.
Reality/Vision, XHTTP, Hysteria2 and AmneziaWG MUST have positive implementation
paths. Generic connected state, server liveness, direct traffic, another target,
handshake-only evidence or cached success MUST NOT qualify.

#### Scenario: Relay candidate succeeds

- **WHEN** an exact declared relay candidate completes its authenticated transport,
  HTTP response body and owned runtime cleanup during a stable test scope
- **THEN** the client records candidate-path evidence for that delivery and target
- **AND** it makes no claim that the user's persistent selected runtime changed

#### Scenario: Active AmneziaWG target succeeds

- **WHEN** the user has connected the exact imported AmneziaWG target and explicitly
  runs a test while its consumed configuration and runtime generation remain current
- **THEN** a completed HTTP body through that AWG runtime qualifies as tested evidence

#### Scenario: Old AWG runtime or failed target remains unverified

- **WHEN** the active AWG profile ID matches but its consumed endpoint, keys,
  obfuscation or carrier differs from the imported target, or the test fails
- **THEN** the client retains received-only state and reports the actionable reason
- **AND** it never falls back to an old or direct path to manufacture success

### Requirement: REQ-MIGRATION-FENCE — Reject stale asynchronous evidence

The client MUST correlate test results with the captured delivery, imported
target configuration, runtime ownership and physical network generation. It MUST
discard late results after cancellation, refresh, profile mutation, ownership
change, network change or runtime termination. Receipt persistence MUST compare
the captured identity atomically with the current subscription.

#### Scenario: Refresh races with a successful probe

- **WHEN** a new delivery or modified target is persisted while an earlier probe runs
- **THEN** the earlier result cannot promote the current delivery to tested

#### Scenario: Runtime changes during AWG test

- **WHEN** AWG stops, restarts, changes consumed configuration or loses its runtime
  ownership token while the request is in flight
- **THEN** even an HTTP success is rejected as stale evidence

### Requirement: REQ-MIGRATION-EXPORT — Export explicit bounded private receipts

The client MUST expose received-only and tested JSON receipts through explicit
local export or share. The receipt MUST use the shared versioned fixed-field
authenticated contract and MUST omit receipt keys, VPN credentials, endpoint
addresses, device names, subscription URLs and network identifiers. No automatic
upload, mandatory telemetry or additional backend MUST be introduced.

#### Scenario: Owner exports a tested receipt

- **WHEN** the owner chooses export after a successful exact-target test
- **THEN** the exported receipt binds delivery identity, raw-body digest, test scope
  and result using the per-delivery key without exporting that key

#### Scenario: Owner exports before testing

- **WHEN** the owner chooses export for a received but untested delivery
- **THEN** the exported receipt explicitly reports received-only evidence
- **AND** it cannot be interpreted as authorization to retire the old server

#### Scenario: Privacy remains local

- **WHEN** a delivery is received or tested without an export action
- **THEN** no receipt leaves the device and no external notification is sent
