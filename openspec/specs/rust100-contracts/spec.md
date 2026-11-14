# rust100-contracts Specification

## Purpose
Make impossible Rust results explicit while preserving existing transport, cleanup, and validation behavior.

## Requirements

### Requirement: REQ-R100-IMPOSSIBLE — Uninhabited results

TCP-only relay implementations SHALL use an uninhabited datagram type and retain Unsupported errors and disabled UDP capabilities. Indefinite helper listeners SHALL expose only their possible completion modes.

#### Scenario: Unsupported datagram

- **WHEN** a caller requests a datagram from SSH, Mieru, ShadowTLS, or a relay chain
- **THEN** the operation returns Unsupported and cannot construct a successful datagram value.

#### Scenario: Listener lifecycle

- **WHEN** an accept error is retryable or the listener future is cancelled
- **THEN** retries and cancellation retain their current behavior; normal completion is not introduced.

### Requirement: REQ-R100-CLEANUP — Preserve cleanup boundaries

Standard guards SHALL preserve proxy state restoration and exactly one DNS outcome for each started attempt.

#### Scenario: Cancel a DNS attempt

- **WHEN** an unpolled exchange is dropped
- **THEN** no attempt is recorded.

#### Scenario: Drop a pending exchange

- **WHEN** an exchange is dropped after its resolver request becomes pending
- **THEN** exactly one failed observation is recorded for that resolver.

#### Scenario: Complete an exchange

- **WHEN** a resolver attempt completes
- **THEN** the guard is dismissed before its result is recorded, with no duplicate observation.

#### Scenario: Proxy unwind

- **WHEN** a blocking proxy run unwinds while its state is Running
- **THEN** its cleanup restores Idle; normal completion retains its current explicit state transition.

### Requirement: REQ-R100-VALUES — Preserve normalization and ordering

The refactor SHALL retain DNS validation, remove only one optional root dot, and keep deployment construction and rotation order. Existing socket protection and privacy checks SHALL remain in force.

#### Scenario: Repeated root dot

- **WHEN** a DNS identity contains two final dots
- **THEN** removing one optional root dot leaves an invalid empty label and the identity is rejected.

#### Scenario: Deployment rotation

- **WHEN** the deployment list is empty or rotation starts at a nonzero index
- **THEN** the result remains empty or wraps in the same ascending offset order before cooldown sorting.
