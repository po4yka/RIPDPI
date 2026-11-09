# http-relay-carrier-contracts Specification

## Purpose
Preserve the protocol and resource contracts of existing HTTP relay carriers under peer errors and caller cancellation.

## Requirements

### Requirement: REQ-STD10-CANCEL — Isolate cancellation

The implementation MUST keep active logical streams usable when another opening on the same carrier is cancelled.

#### Scenario: Isolate cancellation

- **WHEN** a second stream opening is cancelled while the first stream is active
- **THEN** the first stream continues to exchange bytes and the cancelled opening releases its resources.

### Requirement: REQ-STD10-SETTINGS — Validate carrier capabilities

The implementation MUST wait for peer Extended CONNECT permission and validate HTTP Datagram negotiation before it sends the corresponding requests or datagrams.

#### Scenario: Validate carrier capabilities

- **WHEN** the peer omits or disables a required setting or sends an invalid boolean setting
- **THEN** the client sends no forbidden request or datagram and returns a bounded protocol failure.

### Requirement: REQ-STD10-SUCCESS — Carry valid CONNECT success responses

The implementation MUST carry tunnel bytes after a valid non-200 successful H2 CONNECT response.

#### Scenario: Carry valid CONNECT success responses

- **WHEN** a peer accepts a TCP or UDP tunnel with 201
- **THEN** the caller can exchange payloads through that tunnel.

### Requirement: REQ-STD10-CAPSULE — Enforce capsule framing

The implementation MUST abort the request stream on malformed capsules, a truncated final capsule or a Context-0 UDP payload above 65527 bytes.

#### Scenario: Enforce capsule framing

- **WHEN** a malformed or oversized capsule precedes a valid datagram
- **THEN** the stream ends with a protocol error and the later datagram is not delivered.

### Requirement: REQ-STD10-HEADERS — Validate capsule response fields

The implementation MUST reject capsule tunnel responses with Content-Length, Content-Type or Transfer-Encoding.

#### Scenario: Validate capsule response fields

- **WHEN** a successful CONNECT-UDP response includes a forbidden field
- **THEN** the client refuses the tunnel before it sends user payload.

### Requirement: REQ-STD10-DOCS — Describe actual runtime consumers

The implementation MUST document the separate Rust H2 and Chromium H2/H3 Naive consumers.

#### Scenario: Describe actual runtime consumers

- **WHEN** a maintainer reads the carrier support documentation
- **THEN** the described runtime selection agrees with service admission and launch code.
