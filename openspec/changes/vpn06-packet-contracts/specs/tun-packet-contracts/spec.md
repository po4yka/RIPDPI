## Purpose

Preserve valid TUN traffic and reject malformed packets consistently across the native packet owners.

## ADDED Requirements

### Requirement: REQ-VPN06-F1 — Validate IPv4 headers

The tunnel MUST reject an invalid IPv4 header checksum before reassembly or forwarding.

#### Scenario: Validate IPv4 headers

- **WHEN** a UDP packet or fragment has an invalid IPv4 header checksum
- **THEN** no UDP association, strategy injection or reassembly state accepts that packet.

### Requirement: REQ-VPN06-F2 — Apply IPv6 option policy

The tunnel MUST check Hop-by-Hop and Destination Options lengths and unknown-option actions before forwarding.

#### Scenario: Apply IPv6 option policy

- **WHEN** an unknown option requires discard or an option exceeds its header
- **THEN** the packet does not reach a transport or raw injector.

### Requirement: REQ-VPN06-F3 — Reject overlapping IPv6 fragments

The tunnel MUST discard an IPv6 datagram when fragments overlap and retain bounded rejection state until its fixed deadline.

#### Scenario: Reject overlapping IPv6 fragments

- **WHEN** partially overlapping fragments and later fragments use the same identity
- **THEN** no datagram is forwarded and repeated traffic does not extend the deadline.

### Requirement: REQ-VPN06-F4 — Retain fragmentable extension headers

The tunnel MUST reassemble UDP bytes from the Fragment header boundary and retain supported extension headers.

#### Scenario: Retain fragmentable extension headers

- **WHEN** a valid UDP datagram has Destination Options after its Fragment header
- **THEN** the original UDP payload reaches forwarding once in any fragment order.

### Requirement: REQ-VPN06-F5 — Accept supported IPv6 TCP extensions

The tunnel MUST accept padding Destination Options and atomic Fragment headers without altering TCP data.

#### Scenario: Accept supported IPv6 TCP extensions

- **WHEN** a valid TCP SYN includes those headers, alone or after a valid Hop-by-Hop header
- **THEN** the tunnel returns a valid SYNACK instead of an unrecognized-next-header error.

### Requirement: REQ-VPN06-F6 — Use checked transport ranges

The tunnel MUST reject invalid IP and transport lengths or checksums before raw strategy execution and limit data to declared ranges.

#### Scenario: Use checked transport ranges

- **WHEN** a packet is truncated or has bytes outside its IP or UDP payload
- **THEN** the raw path rejects it or uses only the declared valid transport payload.

### Requirement: REQ-VPN06-F7 — Include SYNACK data in checksum

The tunnel MUST include the complete bounded TCP header and payload when changing sequence.

#### Scenario: Include SYNACK data in checksum

- **WHEN** a SYNACK contains odd-length data and options
- **THEN** the new packet has a valid checksum and retains the original data.

### Requirement: REQ-VPN06-F8 — Bound IPv6 output size

The tunnel MUST reject output that exceeds the ordinary IPv6 payload length field.

#### Scenario: Bound IPv6 output size

- **WHEN** extension headers, TCP header and data exceed 65535 bytes together
- **THEN** the builder returns failure without a wrapped length.

### Requirement: REQ-VPN06-IO — Preserve packet writes and lifecycle

The tunnel MUST treat a short packet write as an error and retain cancellation and non-root behavior.

#### Scenario: Preserve packet writes and lifecycle

- **WHEN** a packet write returns fewer bytes than requested or cancellation occurs during readiness wait
- **THEN** the tunnel reports failure or cancellation without counting a full successful write.
