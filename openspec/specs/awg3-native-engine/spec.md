# awg3-native-engine Specification

## Purpose
Run AmneziaWG 3.x configs from current AmneziaVPN servers, including their non-zero S3/S4, without leaking the header protection key.

## Requirements

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

### Requirement: REQ-AWG3-FLOOR — Lift the S3/S4 floor on arm64 evidence

Non-zero S3/S4 MUST activate on every platform once Android arm64 interop evidence shows lossless transfers. The cross-repo policy MUST record that evidence and its scope.

#### Scenario: AmneziaVPN 5.x default config

- **WHEN** a user imports a config with S1-S4 = 12 and a header protection key
- **THEN** the editor fills, activation is accepted, and on the arm64 emulator IPv4/IPv6 TCP and UDP traffic flows with matching SHA-256 and no lost datagram.
