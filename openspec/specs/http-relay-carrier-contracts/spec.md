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

### Requirement: REQ-TRN04-OPEN — Open streaming upload without a response heartbeat

The native xHTTP client MUST return a writable stream-up session after request submission without requiring upload response headers. A rejected or reset upload MUST produce an observable error.

#### Scenario: Server does not flush upload response headers

- **WHEN** the peer accepts the GET and waits for upload payload before flushing POST response headers
- **THEN** the client returns a writable stream and completes a payload exchange.

### Requirement: REQ-TRN04-DROP — Close each stream's HTTP resources

The client MUST terminate the HTTP requests and tasks owned by a fully dropped logical stream while keeping active sibling streams usable. Write-half shutdown MUST preserve the download half.

#### Scenario: Drop an idle stream on a reused carrier

- **WHEN** a completed stream is dropped while the peer leaves its responses open without data
- **THEN** its peer response bodies and tasks close within a bounded interval and a sibling still exchanges bytes.

### Requirement: REQ-TRN04-URL — Preserve configured URL semantics

The client MUST preserve path slashes and query data and encode the decoded path as the pinned Xray URL implementation does. The repository-owned Cloudflare origin helper MUST decode inbound request paths exactly once, match the same configured base and reject malformed escapes or invalid session boundaries before session allocation.

#### Scenario: Path contains percent characters and repeated slashes

- **WHEN** both peers use a path with a literal percent character, repeated terminal slash or a query ending in slash
- **THEN** the request matches the peer path and preserves the query value in both supported modes.

#### Scenario: Shared client reaches the local origin helper

- **WHEN** an HTTP request carries the client's encoded percent character or repeated-slash base
- **THEN** the origin helper admits the matching session and rejects invalid percent escapes or encoded extra session segments.

### Requirement: REQ-TRN04-POOL — Use capacity on any carrier

A queued pool acquisition MUST use any carrier that becomes available without exceeding physical or logical limits.

#### Scenario: Second carrier becomes free first

- **WHEN** two carriers are full and only the second carrier releases capacity
- **THEN** the pending acquisition completes on that carrier while the first remains busy.

### Requirement: REQ-TRN04-DOCS — Describe the supported xHTTP contract

The documentation MUST distinguish mode selectors from download legs and record the verified upstream commit and cancellation behavior.

#### Scenario: Read the support contract

- **WHEN** a maintainer checks supported modes and cleanup ownership
- **THEN** the documents agree with the current code and pinned source.
