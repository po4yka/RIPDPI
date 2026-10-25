## Purpose

Run AmneziaWG 3.x configs from current AmneziaVPN servers without weakening the arm64 S3/S4 floor or leaking the header protection key.

## ADDED Requirements

### Requirement: REQ-AWG3-WIRE — Match the upstream AWG 3.1 wire format

The native codec MUST interoperate with amneziawg-go v3.1.20260828 for header protection, random trailers, content padding and header ranges.

#### Scenario: Live upstream peer

- **WHEN** RIPDPI's runtime connects to an amneziawg-go v3.1 peer with header protection, padding, trailers and S1-S4 = 12
- **THEN** the handshake completes and IPv4/IPv6 TCP and UDP traffic flows, and a wrong header protection key fails every session.

### Requirement: REQ-AWG3-HOSTILE — Hostile datagrams cannot panic or inflate the tunnel

The codec MUST NOT panic on any inbound datagram, and the padding window MUST grow only from authenticated transport packets.

#### Scenario: Spoofed large datagram

- **WHEN** an unauthenticated 65 KB datagram with a valid transport header arrives
- **THEN** it is dropped and the padding window does not change.

### Requirement: REQ-AWG3-CONTRACT — Carry 3.x keys without breaking stored data

Every new field MUST be optional with a safe default. The header protection key MUST stay out of the Room blob, logs and `toString`. Keys that earlier builds ignored MUST still import.

#### Scenario: Older profile

- **WHEN** a profile saved before this change loads
- **THEN** it activates as before.

### Requirement: REQ-AWG3-FLOOR — Keep the S3/S4 floor

Non-zero S3/S4 MUST still be refused at activation, with an error that names the floor and no secret value.

#### Scenario: AmneziaVPN 5.x default config

- **WHEN** a user imports a config with S1-S4 = 12 and a header protection key
- **THEN** the editor fills, shows that the floor blocks it, and activation is refused.
