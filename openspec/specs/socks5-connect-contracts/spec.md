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
