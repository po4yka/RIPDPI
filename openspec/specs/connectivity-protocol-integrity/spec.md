# connectivity-protocol-integrity Specification

## Purpose
Keep connectivity evidence, relay framing, and configuration changes correct under network and lifecycle failures.

## Requirements

### Requirement: REQ-AUDIT-DIAGNOSTICS — Current-path evidence

Connectivity probes MUST try the ordered target addresses, preserve the original TLS name, accept UDP only from the expected peer, and obtain DNS measurements for the current scan. HTTP target parsing MUST support IPv6 authorities and queries without a path. QUIC success MUST require a valid response related to the sent packet; arbitrary payloads and invalid Retry tags MUST fail.

#### Scenario: Current-path evidence

- **WHEN** a first address fails, a stale DNS result exists, or an unrelated UDP packet arrives
- **THEN** the probe uses a working alternate address and current DNS data, and rejects unrelated or malformed responses

### Requirement: REQ-AUDIT-RELAY — Complete authenticated relay frames

SOCKS handshakes MUST write complete frames. Shadowsocks TCP MUST expose an application stream without waiting for response payload. Shadowsocks 2022 MUST use SIP022 initial headers, timestamp and request-salt validation, and UDP recipient validation before replay state changes. Replay-window arithmetic MUST remain valid at the integer boundary.

#### Scenario: Complete authenticated relay frames

- **WHEN** a stream accepts partial writes, a target waits for client data, or a response has a wrong session, salt, timestamp or replay ID
- **THEN** valid traffic completes and invalid traffic is rejected without corrupting session state

### Requirement: REQ-AUDIT-DNS — DoQ and pinned TLS authentication

DoQ MUST send zero DNS message IDs, validate response framing and restore the caller ID. Pinned TLS MUST verify handshake signatures with the certificate key.

#### Scenario: DoQ and pinned TLS authentication

- **WHEN** a caller uses a nonzero DNS ID or an attacker copies a pinned certificate without its private key
- **THEN** the DoQ wire ID is zero and the caller ID is restored; the forged TLS signature is rejected

### Requirement: REQ-AUDIT-ANDROID — Atomic and cancellable configuration

Support settings MUST preserve unrelated concurrent settings, reject malformed embedded messages without a crash, and report storage errors with a terminal retryable state. WARP Amnezia normalization MUST preserve valid ordered junk ranges and native numeric bounds. Runtime readiness MUST preserve caller cancellation and report only its own deadline as a startup timeout.

#### Scenario: Atomic and cancellable configuration

- **WHEN** a concurrent settings update, malformed activation filter, storage IOException, or outer timeout occurs
- **THEN** unrelated fields remain intact, the UI exits busy state, and cancellation is not replaced with a startup error

### Requirement: REQ-AUDIT-EVIDENCE — Reviewable audit evidence

The audit MUST list component coverage, confirmed defects, changes, executed checks and remaining limits. It MUST keep local, hosted CI, device, artifact and deployment results separate.

#### Scenario: Reviewable audit evidence

- **WHEN** the corrected tree is prepared for integration
- **THEN** the report names the exact source revision and the observed result of each required gate

### Requirement: REQ-AUDIT-CAPTURE — Capture ownership and bounded detection

PCAP start MUST either publish stop-capable ownership or clean up the native capture when the caller is cancelled. Retention MUST remove expired completed captures from the actual production directory and preserve the current live capture. Stale active markers MUST NOT exempt completed captures indefinitely. HTTP proxy detection MUST read a bounded complete status line independent of TCP segmentation.

#### Scenario: Cancellation, expired capture and fragmented proxy response

- **WHEN** cancellation follows native start, a completed capture expires, or an HTTP status arrives in fragments
- **THEN** capture ownership remains consistent, the expired real file is removed without deleting a live capture, and valid HTTP status is recognized within the existing time and size limits

### Requirement: REQ-AUDIT-ECH
MASQUE ECH bootstrap sockets SHALL retain the carrier socket protection policy. Source binding SHALL NOT replace VPN protection. Required protection SHALL fail closed when the callback is missing or rejects a socket.

#### Scenario: VPN bootstrap without protection
- **WHEN** a MASQUE ECH lookup runs with VPN-required policy and no usable protection callback
- **THEN** TCP fails before connect and UDP fails before outbound use

#### Scenario: Inactive host lookup
- **WHEN** the runtime is inactive
- **THEN** ECH lookup sockets work without a VPN callback

### Requirement: REQ-AUDIT-IPC
The root-helper IPC receiver SHALL close every received file descriptor when it rejects a truncated or multiple-descriptor control message.

#### Scenario: Truncated descriptor control message
- **WHEN** a peer sends more descriptors than the ancillary buffer can hold
- **THEN** the receiver rejects the message and releases every installed descriptor
