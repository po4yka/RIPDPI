## Purpose

Extend verified local CONNECT contracts with cancellation, accurate error replies, and valid absolute domain names.

## ADDED Requirements

### Requirement: REQ-S5-STOP — Stop upstream negotiation

The implementation MUST stop owned upstream SOCKS negotiation sockets during forced drain and refuse CONNECT writes after shutdown.

#### Scenario: Stop upstream negotiation

- **WHEN** a loopback upstream withholds auth until after owner shutdown
- **THEN** the upstream observes closure and receives no later CONNECT.

### Requirement: REQ-S5-TIMEOUT — Timeout classification

The implementation MUST report generic connection or socket timeout as general failure unless specific protocol evidence supplies a different reply.

#### Scenario: Timeout classification

- **WHEN** a connect error is TimedOut or WouldBlock without TTL evidence
- **THEN** the SOCKS client receives REP 01, never inferred TTL expiry.

### Requirement: REQ-S5-UPSTREAM-REP — Upstream error fidelity

The implementation MUST preserve valid upstream SOCKS failure replies while rejecting invalid reply framing or unassigned codes as general failure.

#### Scenario: Upstream error fidelity

- **WHEN** the owned upstream returns a valid REP 03, 04, or 06
- **THEN** the local client receives the same validated failure code.

### Requirement: REQ-S5-ABSOLUTE-NAME — Absolute domain names

The implementation MUST accept nonempty SOCKS domain names and resolve absolute names with one terminal root dot without an empty DNS label.

#### Scenario: Absolute domain names

- **WHEN** a client requests a. or abc.test. through a controlled local resolver
- **THEN** resolution and CONNECT succeed and the root dot is encoded as the DNS root terminator.
