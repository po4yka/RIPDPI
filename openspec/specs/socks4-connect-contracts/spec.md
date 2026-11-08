# socks4-connect-contracts Specification

## Purpose
Keep SOCKS4 and SOCKS4a CONNECT behavior consistent across request parsing, routing, DNS, and TCP close.

## Requirements

### Requirement: REQ-S4-CONNECT — Connect before success

The proxy MUST establish a usable upstream connection before it sends SOCKS4 success.

#### Scenario: Connect before success

- **WHEN** a SOCKS4 or SOCKS4a CONNECT target or upstream refuses the connection, including delayed routes
- **THEN** the client receives one eight-byte 0x5b reply and no 0x5a reply

### Requirement: REQ-S4-FIN — Preserve directional close

The proxy MUST preserve the open TCP direction when either peer sends FIN before the first payload.

#### Scenario: Preserve directional close

- **WHEN** the client sends an empty FIN and the origin replies after EOF
- **THEN** the reply reaches the client; an origin FIN must reach the client without waiting for client payload

### Requirement: REQ-S4-FAMILY — Apply the address family policy

The proxy MUST reject a resolved IPv6 target when IPv6 is disabled.

#### Scenario: Apply the address family policy

- **WHEN** a SOCKS4a request uses an IPv6 literal or resolves to IPv6 with IPv6 disabled
- **THEN** the proxy sends 0x5b and opens no IPv6 origin connection

### Requirement: REQ-S4-CANCEL — Bound handshake DNS ownership

Handshake DNS MUST stop on runtime cancellation or the absolute handshake deadline.

#### Scenario: Bound handshake DNS ownership

- **WHEN** a local DNS server stalls after a complete SOCKS4a request and the owner stops or the deadline expires
- **THEN** the DNS socket closes, the client slot is released, and runtime stop needs no forced abort

### Requirement: REQ-S4-PARSE — Validate the exact request boundary

The parser MUST use 0.0.0.x with nonzero x as the SOCKS4a sentinel and validate version and NUL boundaries.

#### Scenario: Validate the exact request boundary

- **WHEN** the parser receives zero IPv4, a nonzero sentinel, a wrong version, or a missing terminator
- **THEN** only the nonzero sentinel selects domain parsing; malformed requests fail and bytes after the consumed request stay application data

### Requirement: REQ-S4-LIMIT — Reply at the request size limit

The proxy MUST retain bounded request storage and send SOCKS4 failure for a size rejection after version detection.

#### Scenario: Reply at the request size limit

- **WHEN** a NUL-terminated USERID exceeds the local request limit
- **THEN** the client receives one eight-byte 0x5b reply before closure; the largest supported USERID still connects

### Requirement: REQ-S4-COMPAT — Keep the declared scope

The proxy MUST retain CONNECT-only operation, token rejection, domain length limits, and supported listener modes.

#### Scenario: Keep the declared scope

- **WHEN** a client uses BIND, a required auth token, a forbidden domain size, or a valid request in Mixed or BytePrefixed mode
- **THEN** unsupported inputs fail; valid IPv4 and domain requests preserve exact bytes and existing protocol contracts
