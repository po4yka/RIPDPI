## Purpose

Keep the accepted, encrypted selector member set available for real-payload failover without depending on online subscription delivery.

## ADDED Requirements

### Requirement: REQ-RESERVE-RELOAD — Retain accepted candidates offline

The client MUST persist the complete accepted selector member set and its payload policy using the existing private encrypted storage, and retain the selected member ID in its existing private selection preferences. A restarted client MUST be able to load these candidates without a subscription request and select a reserve only after the configured real-payload verification succeeds. Prior successful selection MUST NOT substitute for a fresh payload observation.

#### Scenario: Restart while subscription delivery is unavailable

- **WHEN** the client reloads saved primary and reserve profiles while refresh fails
- **THEN** their credentials and policy remain available and real local HTTPS/SOCKS payload verification can select the working reserve when the primary fails
- **AND** an empty or wrong-digest response cannot qualify a reserve

#### Scenario: All candidates fail

- **WHEN** all saved candidates fail their current payload tests
- **THEN** the client retains the last proxy selection without routing protected traffic directly or declaring a healthy path

### Requirement: REQ-RESERVE-STORAGE — Fail closed on unreadable saved state

The client MUST distinguish absent from unreadable sealed data, preserve unreadable bytes without plaintext fallback, and load at guarded startup. Ciphertext and schema metadata MUST share one durable commit. A failed write MUST NOT publish the proposed catalogue.

#### Scenario: Corrupt encrypted catalogue alongside legacy data

- **WHEN** sealed data is present but cannot be authenticated or decoded
- **THEN** the storage read fails and preserves its bytes rather than restoring obsolete plaintext or overwriting the reserve set

#### Scenario: Corrupt storage at startup

- **WHEN** saved storage is unreadable during startup
- **THEN** dependency construction and early collectors perform no storage I/O, readiness fails before recovery mutations, and retry rechecks storage without clearing it
- **AND** collectors remain active without an empty fallback and receive the first validated snapshot after a successful retry

#### Scenario: Storage write fails

- **WHEN** a durable catalogue write fails
- **THEN** the repository reports failure and does not publish the proposed new member set as committed

### Requirement: REQ-RESERVE-REFRESH — Publish only current usable refresh results

A refresh MUST preserve accepted reserves and their payload policy on failure. Only a usable result for the still-current source and catalogue snapshot may replace them. Obsolete results MUST NOT report installation, change newer metadata or recreate deleted state. Delivery-token expiry MUST NOT become transport-credential expiry.

#### Scenario: Invalid or unsupported update

- **WHEN** delivery returns invalid content or only unsupported members
- **THEN** the existing profiles and policy remain, the result is a failure, and last successful refresh time does not advance

#### Scenario: Catalogue changes during fetch

- **WHEN** a refresh completes after the source, accepted members or group existence changed
- **THEN** it cannot overwrite the newer catalogue or report itself as installed

#### Scenario: Authoritative replacement

- **WHEN** a valid current update removes a reserve
- **THEN** the removed profile is absent after restart and is not restored from a historical backup
