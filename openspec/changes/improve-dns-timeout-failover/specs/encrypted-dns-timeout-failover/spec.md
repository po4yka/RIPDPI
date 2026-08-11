## Purpose

Bound VPN startup delay when encrypted DNS paths time out while preserving
strict encrypted-only resolution and allowing transiently unavailable paths to
recover in later sessions.

## ADDED Requirements

### Requirement: REQ-EAGER-BOOTSTRAP-TIMEOUT — Fail over after one bootstrap timeout

The implementation MUST select the next eligible encrypted DNS path after the
first timeout failure observed within the current path's first three queries.

#### Scenario: First bootstrap query times out

- **WHEN** the first query on an encrypted DNS path fails with a timeout
- **THEN** the controller activates the next unattempted encrypted DNS path
  without waiting for a second timeout on the current path

#### Scenario: Established path has an isolated timeout

- **WHEN** a timeout occurs after more than three queries on the current path
- **THEN** the controller retains the existing two-consecutive-failure
  threshold before activating another path

### Requirement: REQ-TRANSIENT-TIMEOUT-MEMORY — Do not persist timeout-only blocking

The implementation MUST keep timeout-only path rejection local to the current
failover attempt and MUST NOT persist that path as blocked for the network.

#### Scenario: Timed-out resolver recovers in a later session

- **WHEN** an encrypted DNS path times out during one VPN session and is
  reconsidered in a later session on the same network
- **THEN** the earlier timeout does not exclude the path from the later
  candidate plan

#### Scenario: Structural encrypted transport failure

- **WHEN** an encrypted DNS path fails with a classified TLS or SNI blocking
  signal
- **THEN** the controller may persist that path as blocked for the network

### Requirement: REQ-STRICT-ENCRYPTED-FAILOVER — Never weaken DNS transport

The implementation MUST select failover candidates only from configured
encrypted DNS paths and MUST NOT use plaintext DNS as a timeout fallback.

#### Scenario: Every encrypted candidate times out

- **WHEN** all eligible encrypted DNS paths have been attempted without success
- **THEN** the controller reports the encrypted candidate chain exhausted and
  does not install a plaintext resolver override

### Requirement: REQ-COMPATIBLE-TIMEOUT-POLICY — Keep existing data contracts

The implementation SHALL change only failover decision semantics and SHALL NOT
require wire, protobuf, JNI, configuration, or persisted-data migration.

#### Scenario: Existing network preference data is loaded

- **WHEN** the updated controller reads existing preferred and blocked path
  records
- **THEN** it interprets those records without a schema migration
