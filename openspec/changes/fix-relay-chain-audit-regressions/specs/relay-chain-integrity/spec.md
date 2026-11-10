## Purpose

Keep the selected relay chain intact through saving, startup, reporting, and shutdown without adding protocol modes.

## ADDED Requirements

### Requirement: REQ-CHAIN-PERSIST — Preserve ordered middle hops

The profile mapper MUST save the selected middle hop list and MUST clear it when the kind changes away from chain relay.

#### Scenario: Edit a saved chain

- **WHEN** a saved chain changes, reorders, or removes middle hops
- **THEN** the saved profile and activation route contain the selected ordered list

#### Scenario: Save a new chain or another kind

- **WHEN** a new chain is saved or a chain becomes another kind
- **THEN** the profile contains the new middle list or an empty list for the other kind

### Requirement: REQ-CHAIN-ADMISSION — Reject incomplete positions

The editor and service MUST reject empty selected middle positions before network startup. Every selected hop MUST pass its role checks. Existing supported hop kinds and bounds remain unchanged.

#### Scenario: Empty or invalid middle position

- **WHEN** a selected middle position is blank, missing, or has an unsupported role
- **THEN** startup reports invalid configuration and does not shorten the selected route

### Requirement: REQ-CHAIN-SHUTDOWN — Drain owned AnyTLS work

Chain factory shutdown MUST close and join its AnyTLS pool workers before successful return. Cleanup MUST remain retryable after cancellation and repeated shutdown MUST be safe.

#### Scenario: Idle or active entry carrier

- **WHEN** shutdown follows an idle, active, or cancelled AnyTLS entry open
- **THEN** owned carrier work is closed before successful return, without runtime disposal

#### Scenario: Cancel cleanup

- **WHEN** a shutdown future is cancelled and then retried
- **THEN** unfinished joins remain owned and the retry completes cleanup

### Requirement: REQ-CHAIN-ROLES — Keep configured telemetry roles

Telemetry MUST derive entry, intermediate, and exit roles from the configured hop count. Concurrent connection reports MUST have an explicit aggregate or attempt attribution contract. A new attempt MUST NOT retain stale later-hop success as its own progress.

#### Scenario: Partial startup and retry

- **WHEN** a four-hop attempt fails or is cancelled before exit, including after an earlier success
- **THEN** its entry or middle state is not reported as exit state and stale later-hop progress is absent

### Requirement: REQ-CHAIN-TRUST — Use all hop trust domains

The editor MUST evaluate the full ordered hop trust list with the same shared helper as service admission. This warning MUST NOT change per-hop TLS trust checks.

#### Scenario: Only middle creates a warning

- **WHEN** only a middle hop has missing or shared trust metadata
- **THEN** the editor reports the same trust warning as the service helper
