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

The implementation MUST ensure that an upstream UDP binding is not used after control EOF or reset.

#### Scenario: Invalidate a closed upstream control

- **WHEN** the upstream closes control while its UDP socket remains open
- **THEN** a later client datagram is not sent on that old binding.

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
