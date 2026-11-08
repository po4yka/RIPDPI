# socks5-token-auth Specification

## Purpose
Define bounded token authentication for local SOCKS5 listeners and TUN clients. Reject malformed credentials and replies, preserve credential privacy, and retain correlation for canonical loopback peers.

## Requirements

### Requirement: REQ-LOC03-TOKEN — Listener token byte bounds

A SOCKS5-capable listener MUST reject a configured token outside 1..=255 UTF-8 bytes before bind. HTTP-only token length behavior SHALL remain unchanged.

#### Scenario: Invalid listener token

- **GIVEN** a SOCKS5-capable mode with a token of zero or 256 bytes
- **WHEN** listener creation is requested
- **THEN** it fails with a configuration error before bind and does not expose the token.

#### Scenario: Valid byte boundary

- **GIVEN** a token of 1 or 255 bytes, including multibyte UTF-8
- **WHEN** the client supplies the exact password
- **THEN** authentication succeeds and valid traffic can use the listener.

### Requirement: REQ-LOC03-REPLY — TUN auth reply version

The TUN client MUST accept RFC 1929 authentication only for response bytes 01 00.

#### Scenario: Malformed success reply

- **WHEN** a peer replies with version 00, 02, 05 or FF and status 00
- **THEN** setup fails before CONNECT, ASSOCIATE or payload transmission.

### Requirement: REQ-LOC03-FIELDS — TUN credential byte bounds

Configured username and password MUST both be absent for NoAuth or each contain 1..=255 UTF-8 bytes for UserPass. Both configuration parsing and the handshake boundary MUST reject invalid fields without exposing their contents.

#### Scenario: Empty or oversized field

- **WHEN** a credential field has zero or 256 bytes
- **THEN** configuration validation fails and the helper does not send a malformed authentication frame.

#### Scenario: Valid credentials and NoAuth

- **WHEN** both credentials are absent, or both have valid byte lengths
- **THEN** the selected method and valid wire exchange remain unchanged.

### Requirement: REQ-LOC03-CORRELATION — Canonical loopback correlation

After successful token authentication, valid UTF-8 usernames from canonical loopback peers MUST retain their optional correlation ID. Non-loopback peers and failed authentication MUST NOT produce an ID.

#### Scenario: IPv4-mapped loopback peer

- **WHEN** an authenticated IPv4 loopback peer is represented as an IPv4-mapped IPv6 address
- **THEN** its ID matches the same username received over ordinary IPv4 or native IPv6 loopback.

#### Scenario: Remote peer or malformed ID

- **WHEN** a peer is non-loopback or its username is not valid UTF-8
- **THEN** no correlation ID is produced.

### Requirement: REQ-LOC03-PRESERVE — Authentication security and compatibility

The implementation MUST preserve exact token comparison through the pinned constant-time routine, generic credential-free errors, rejection of incompatible inbound modes and valid TCP/UDP authentication paths.

#### Scenario: Wrong token

- **WHEN** a client sends a wrong token and pipelines a target request
- **THEN** authentication fails without opening the target or logging the credentials.
