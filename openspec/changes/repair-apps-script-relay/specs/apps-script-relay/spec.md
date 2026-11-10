## Purpose

Provide an authenticated HTTP relay with explicit client, peer and certificate trust boundaries.

## ADDED Requirements

### Requirement: REQ-RLY15-AUTH — Auth

The implementation MUST require configured local SOCKS credentials and reject unauthenticated clients.

#### Scenario: Enforce auth boundary

- **WHEN** a client uses the Apps Script relay
- **THEN** it shall require configured local SOCKS credentials and reject unauthenticated clients

### Requirement: REQ-RLY15-BOUNDARIES — Boundaries

The implementation MUST reject unsupported preflight and implicit direct IP traffic.

#### Scenario: Enforce boundaries boundary

- **WHEN** a client uses the Apps Script relay
- **THEN** it shall reject unsupported preflight and implicit direct IP traffic

### Requirement: REQ-RLY15-HTTP — Http

The implementation MUST preserve repeated end-to-end headers, remove hop fields and reject malformed envelopes without panic.

#### Scenario: Enforce http boundary

- **WHEN** a client uses the Apps Script relay
- **THEN** it shall preserve repeated end-to-end headers, remove hop fields and reject malformed envelopes without panic

### Requirement: REQ-RLY15-TRUST — Trust

The implementation MUST store the CA in app-private storage and export only its public certificate with explicit MITM instructions.

#### Scenario: Enforce trust boundary

- **WHEN** a client uses the Apps Script relay
- **THEN** it shall store the CA in app-private storage and export only its public certificate with explicit MITM instructions

### Requirement: REQ-RLY15-CONTROL — Control

The implementation MUST remove the ineffective parallel option from user controls.

#### Scenario: Enforce control boundary

- **WHEN** a client uses the Apps Script relay
- **THEN** it shall remove the ineffective parallel option from user controls

### Requirement: REQ-RLY15-PEER — Peer

The implementation MUST define redirect, body encoding and remote authentication semantics without claiming Google interoperability.

#### Scenario: Enforce peer boundary

- **WHEN** a client uses the Apps Script relay
- **THEN** it shall define redirect, body encoding and remote authentication semantics without claiming Google interoperability

