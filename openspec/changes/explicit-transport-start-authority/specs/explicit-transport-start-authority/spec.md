## Purpose

Preserve the checked user command throughout explicit transport activation without allowing stale authority to start a VPN.

## ADDED Requirements

### Requirement: REQ-TRANSPORT-CAPTURED-AUTHORITY — Preserve captured authority

The service MUST pass the exact captured runtime intent to explicit transport activation through queueing and suspension. It MUST NOT replace that intent with current authority.

#### Scenario: Cold transport activation

- **WHEN** a valid explicit transport command reaches the service queue
- **THEN** the activation callback receives its captured runtime intent before and after suspension
- **AND** cold runtime startup can use that intent

### Requirement: REQ-TRANSPORT-STALE-AUTHORITY — Reject invalid or superseded commands

The service MUST reject explicit transport activation without a captured intent or current explicit guard. It MUST cancel an active activation when user stop supersedes the command, and MUST preserve automatic failover restart behavior.

#### Scenario: Missing or stale command

- **WHEN** the captured intent is absent or the explicit guard becomes stale before execution
- **THEN** activation does not run and the tracked request is rejected

#### Scenario: Stop during activation

- **WHEN** user stop supersedes a suspended activation
- **THEN** the activation is cancelled and its tracked request is rejected before stop completes

#### Scenario: Automatic failover

- **WHEN** an automatic failover restart is queued
- **THEN** the existing restart callback remains in use without an explicit activation requirement
