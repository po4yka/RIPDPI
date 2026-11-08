## Purpose

Deliver proven remote UDP ICMP errors to applications through the owned local SOCKS path without false attribution or new privileges.

## ADDED Requirements

### Requirement: REQ-VPN04-NEGOTIATE — Authenticate the private local association

The implementation MUST negotiate feedback only for an authenticated owned loopback listener with kernel support, bind the exact UDP peer and validate a fresh association nonce. Standard SOCKS5 MUST remain compatible. Downgrade MUST require an explicit unsupported-command reply and a fresh connection.

#### Scenario: Private command is unavailable

- **WHEN** an authenticated peer returns command-not-supported
- **THEN** the tunnel retries standard UDP ASSOCIATE on a fresh authenticated connection.

#### Scenario: A forged envelope arrives

- **WHEN** its nonce, peer, version or length is invalid
- **THEN** the receiver drops it without ICMP output.

### Requirement: REQ-VPN04-REMOTE — Relay proven remote errors

On Linux and Android, the implementation MUST use kernel ICMP-origin error queue records for direct UDP destinations. It MUST retain IPv4 destination-unreachable and time-exceeded, and IPv6 destination-unreachable, packet-too-big and time-exceeded metadata. Local errno and relay-carrier errors MUST NOT become remote target errors.

#### Scenario: A non-root UDP destination is closed

- **WHEN** the kernel reports IPv4 type 3 code 3 or IPv6 type 1 code 4
- **THEN** the private association delivers that remote error and its source.

### Requirement: REQ-VPN04-CORRELATE — Attribute errors to the original datagram

The implementation MUST correlate a kernel error uniquely to a successful send in the current socket and association generation. It MUST suppress ambiguous, stale, transformed, cross-family or unsupported errors. Payload history and retained quotes MUST be bounded in RAM.

#### Scenario: A quoted prefix matches two sends

- **WHEN** either datagram could have caused the error
- **THEN** no application ICMP is emitted.

### Requirement: REQ-VPN04-TUN — Preserve ICMP wire and lifecycle contracts

The tunnel MUST emit the proven type, code, source and MTU with the original validated app-visible quote, correct checksums and IPv6 pseudo-header, within the existing MTU limits. UID admission, flow generation, rate limits, cancellation and revoke MUST still gate output.

#### Scenario: A valid current feedback arrives

- **WHEN** its ID and target match a retained admitted datagram
- **THEN** the TUN receives the corresponding ICMP error with the original quote.

#### Scenario: Feedback arrives after flow replacement

- **WHEN** the retained registration is no longer current
- **THEN** the tunnel discards the feedback.
