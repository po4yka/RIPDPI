# socks5-connect-contracts Specification

## Purpose
Provide accurate local SOCKS5 CONNECT replies and reject invalid input before any target connection.

## Requirements

### Requirement: REQ-S5-SUCCESS — Connect result

The implementation MUST send CONNECT success only after an upstream connection is established.

#### Scenario: Connect result failure

- **WHEN** the target refuses a delayed CONNECT
- **THEN** the client receives failure, never optimistic success.

### Requirement: REQ-S5-HEADER — Request header

The implementation MUST reject a request whose version is not 05 or whose reserved byte is not zero before DNS or connection.

#### Scenario: Request header failure

- **WHEN** a client submits an invalid command header after negotiation
- **THEN** the server rejects it without reaching the target.

### Requirement: REQ-S5-ATYP — Address type reply

The implementation MUST send REP 08 and close the connection for unsupported address types.

#### Scenario: Address type reply failure

- **WHEN** an authenticated client sends ATYP 09
- **THEN** the reply reports address type unsupported.

### Requirement: REQ-S5-POLICY — Policy reply

The implementation MUST send REP 02 for a local target or destination routing policy rejection.

#### Scenario: Policy reply failure

- **WHEN** a client requests the listener itself
- **THEN** the request is denied with REP 02.

### Requirement: REQ-S5-AUTH — Authentication framing

The implementation MUST reject zero-length usernames and passwords in RFC 1929 subnegotiation.

#### Scenario: Authentication framing failure

- **WHEN** a client submits an empty username with the correct token
- **THEN** authentication fails and the target is not opened.

### Requirement: REQ-S5-COMPAT — Valid clients

The implementation MUST preserve valid IPv4, IPv6, domain, fragmented and coalesced CONNECT requests, token authentication, server-first traffic, and TCP half-close delivery.

#### Scenario: Response after client FIN

- **WHEN** a valid client sends its request and closes its sending direction
- **THEN** the response is delivered through the open receiving direction.

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
