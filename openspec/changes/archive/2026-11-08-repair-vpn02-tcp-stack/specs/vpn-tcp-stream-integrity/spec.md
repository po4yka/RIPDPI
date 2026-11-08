## Purpose

Keep the local VPN TCP byte stream reliable and bound session resources without root access.

## ADDED Requirements

### Requirement: REQ-VPN02-HALF-CLOSE — Reliable half-close

The implementation MUST deliver all accepted stream bytes before normal EOF and retain TCP TIME-WAIT independently of relay resources.

#### Scenario: Reliable half-close

- **WHEN** the upstream half-closes and the application sends data followed by FIN
- **THEN** the peer receives the exact remaining bytes and a repeated FIN receives an ACK.

### Requirement: REQ-VPN02-IDLE-CLEANUP — Terminal relay cleanup

The implementation MUST retire a failed relay session after an upstream EOF without waiting for another application packet.

#### Scenario: Terminal relay cleanup

- **WHEN** the upstream closes its send direction and the configured relay idle budget expires
- **THEN** the failed relay outcome releases active session resources within the bounded drain period.

### Requirement: REQ-VPN02-INTERFACE-ENTROPY — Fresh interface entropy

The implementation MUST initialize every new tunnel interface with fresh OS entropy and fail setup when entropy cannot be read.

#### Scenario: Fresh interface entropy

- **WHEN** two tunnel instances accept the same first TCP tuple
- **THEN** their initial sequence streams do not repeat.

### Requirement: REQ-VPN02-BUFFER-POLICY — Configured native TCP buffers

The implementation MUST apply the validated tcp-buffer-size value to each smoltcp RX and TX buffer.

#### Scenario: Configured native TCP buffers

- **WHEN** a caller sets the native TCP buffer size to 1024 bytes
- **THEN** the socket capacity and unscaled receive window do not exceed the configured capacity.

### Requirement: REQ-VPN02-IPV6-MSS — Address-family MSS defaults

The implementation MUST use a default send MSS of 1220 for IPv6 and 536 for IPv4 when no nonzero MSS option is received.

#### Scenario: Address-family MSS defaults

- **WHEN** an IPv6 SYN omits MSS or uses MSS zero
- **THEN** the default MSS is 1220 and emitted packets remain within the device MTU.
