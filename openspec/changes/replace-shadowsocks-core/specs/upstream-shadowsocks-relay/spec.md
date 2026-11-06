## Purpose

Use the upstream Shadowsocks protocol implementation while retaining the application's transport, clock, and resource ownership guarantees.

## ADDED Requirements

### Requirement: REQ-SS-UPSTREAM — Upstream protocol ownership

The relay MUST use the pinned upstream implementation for SIP004 and SIP022 framing, cryptography, address encoding, and key derivation. It MUST accept the existing six AEAD methods and valid AES SS2022 identity-key chains, and reject other ciphers and invalid keys before network access. Legacy AEAD remains TCP-only; SS2022 supports TCP and UDP.

#### Scenario: Identity-key profile

- **WHEN** an AES SS2022 profile includes a valid identity-key chain
- **THEN** it exchanges application data with an upstream server using EIH.

#### Scenario: Invalid credentials

- **WHEN** a profile specifies an unsupported cipher or invalid SS2022 key
- **THEN** construction fails without opening a socket or disclosing the key.

### Requirement: REQ-SS-PROTECTION — Protected transport ownership

The relay MUST protect direct non-loopback carrier sockets before outbound use and retain explicit address-family binding. It MUST accept supplied chained TCP transports without creating an extra connection. Cancellation and drop MUST release owned resources; write shutdown MUST retain the response direction.

#### Scenario: Server-first protocol and half-close

- **WHEN** the destination sends data before the client writes, or sends a final response after client write shutdown
- **THEN** the caller receives that data without a stalled handshake or detached pump.

### Requirement: REQ-SS-REPLAY — Authenticated bounded time and replay policy

The implementation MUST use NetworkTimeProvider for SIP022 timestamps, reject stale or duplicate packets and mismatched client sessions, bound server-session replay state, and calibrate only from accepted authenticated server timestamps. Datagram cancellation MUST NOT reuse a packet ID.

#### Scenario: Invalid datagram before a valid reply

- **WHEN** an invalid tag, stale timestamp, wrong client session, or duplicate packet arrives before a valid reply
- **THEN** the association drops the invalid packet, preserves its replay guarantees, and returns the valid payload.

### Requirement: REQ-SS-VALIDATION — Integration evidence

The change MUST remove the private codec and update its callers. Tests MUST cover the supported cipher matrix, identity keys, stream ownership, protected transport, and UDP rejection paths. Upstream source provenance and local patch differences MUST be reproducible.

#### Scenario: Source update

- **WHEN** the vendored dependency is compared with its pinned release
- **THEN** only the documented clock, authenticated metadata, replay-filter exposure, and safety patches differ.
