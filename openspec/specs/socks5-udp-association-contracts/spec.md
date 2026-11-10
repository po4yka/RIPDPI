# socks5-udp-association-contracts Specification

## Purpose
Keep SOCKS5 UDP association state valid across shutdown, peer replies, profile gates and datagram size limits.

## Requirements

### Requirement: REQ-UDP-SETUP-CANCEL — Cancel pending setup

The implementation MUST ensure that stop closes pending setup and joins its worker before returning.

#### Scenario: Cancel pending setup

- **WHEN** the upstream delays its handshake and the runtime stops
- **THEN** completing that handshake after stop cannot send the queued payload.

### Requirement: REQ-UDP-CONTROL-LIFETIME — Invalidate a closed upstream control

The implementation MUST ensure that proxy upstream and TUN client UDP bindings are not used after observed control EOF, reset, or unexpected control data. A later datagram MUST use a new association rather than the old relay socket.

#### Scenario: Invalidate a closed upstream control

- **WHEN** the upstream closes control while its UDP socket remains open
- **THEN** a later client datagram is not sent on that old binding.

#### Scenario: Reject a stale TUN downlink

- **WHEN** a TUN association receives a queued relay reply after control closure is observed
- **THEN** it does not deliver that reply to the TUN.

### Requirement: REQ-UDP-REPLY-AUTHORITY — Validate upstream reply authority

The implementation MUST ensure that an unexpected upstream reply peer is not mapped to the requested target.

#### Scenario: Validate upstream reply authority

- **WHEN** a reply names another IP or port
- **THEN** the reply is dropped instead of being relabelled as the requested peer.

### Requirement: REQ-UDP-UDP-GATE — Apply the profile UDP gate

The implementation MUST ensure that a disabled profile rejects UDP ASSOCIATE before opening or using a UDP association session.

#### Scenario: Apply the profile UDP gate

- **WHEN** udpEnabled is false for any relay kind
- **THEN** UDP ASSOCIATE is rejected without opening or using a UDP association session.

### Requirement: REQ-UDP-DATAGRAM-SIZE — Isolate oversized datagrams

The implementation MUST ensure that an oversized outgoing datagram is dropped without ending the association.

#### Scenario: Isolate oversized datagrams

- **WHEN** an SS2022 frame exceeds the carrier or operating-system size ceiling
- **THEN** the next small datagram still uses that association.

### Requirement: REQ-UDP-SOURCE-STABILITY — Keep a SOCKS association endpoint stable

The implementation MUST ensure that source-port rebind does not replace the endpoint within a pinned upstream SOCKS association.

#### Scenario: Keep a SOCKS association endpoint stable

- **WHEN** rebind policy is enabled with an upstream SOCKS hop
- **THEN** later datagrams continue to use a valid upstream association.

### Requirement: REQ-UDP-LOCAL-SESSION — Negotiate authenticated local UDP authority

An authenticated loopback diagnostic SOCKS client MUST prebind its UDP source and attempt the existing RUF1 association before sending payload. It MUST validate session response frames and correlate error frames with the requested destination. Only an explicit unsupported-command reply may select standard SOCKS fallback on a new control connection. External or unauthenticated standard SOCKS behavior MUST remain available.

#### Scenario: Managed Xray peer

- **WHEN** a local authenticated peer accepts the private command
- **THEN** the diagnostic client sends session-wrapped requests and accepts only correctly wrapped responses from its relay.

#### Scenario: Existing standard peer

- **WHEN** a local peer explicitly rejects the private command as unsupported
- **THEN** the client starts a new authenticated standard association; malformed or denied private replies do not downgrade.
