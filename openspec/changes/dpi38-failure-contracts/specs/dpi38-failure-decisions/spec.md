## Purpose

Keep failure evidence, bounded retry decisions and adaptive learning correct across concurrent rootless proxy flows.

## ADDED Requirements

### Requirement: REQ-DPI38-F1 — Attempt-owned feedback

The runtime MUST apply feedback only to the emitted evolution attempt that produced it. Results MUST be accepted at most once and MUST NOT train another flow, context, or runtime generation.

#### Scenario: Two flows complete out of order

- **WHEN** TLS flow A and QUIC flow B obtain hints and A fails after B starts
- **THEN** only A receives the failure and B can independently receive its own success

### Requirement: REQ-DPI38-F2 — Connection-wide retry budget

The runtime MUST enforce max_route_retries across initial connect, first write, first response and reconnect. A route advance MUST consume budget before another group connects. A no-TFO retry of the same group remains a separate single-use compatibility fallback.

#### Scenario: Zero budget refuses another group

- **WHEN** the initial connection receives a classified response failure and max_route_retries is zero
- **THEN** no alternate connection opens and the original outcome is preserved

### Requirement: REQ-DPI38-F3 — Compatible neutral reprobe

The network reprobe MUST send a valid modern TLS ClientHello. Origin protocol, version, SNI, ALPN or certificate incompatibility MUST NOT by itself reset adaptive state as proof of DPI. A baseline probe MUST NOT claim to test a desync strategy that it did not execute.

#### Scenario: Clean modern origin

- **WHEN** a clean local TLS 1.2 or TLS 1.3 origin receives the built-in reprobe
- **THEN** it can return a ServerHello and no false DPI reset is authorized

### Requirement: REQ-DPI38-F4 — Bounded HTTP observation

The response inspector MUST retain incomplete HTTP headers and a bounded body prefix across reads. It MUST NOT train success from incomplete or limit-truncated evidence.

#### Scenario: HTTP 451 spans reads

- **WHEN** a legal HTTP 451 response is delivered at each split point
- **THEN** classification matches the coalesced response and bytes remain intact

### Requirement: REQ-DPI38-F5 — Version-aware TLS continuation

The response inspector MUST preserve nonfatal TLS 1.2 warning continuation across reads without suppressing fatal TLS 1.3 error alerts. Waiting MUST have byte and time bounds.

#### Scenario: Warning precedes ServerHello

- **WHEN** a TLS 1.2 warning record arrives separately before a valid ServerHello
- **THEN** the read boundary alone does not trigger a strategy failure or success

### Requirement: REQ-DPI38-F6 — TLS envelope validation

The classifier MUST read alert fields only from a complete and valid enclosing record. Incomplete and malformed records MUST NOT become a specific peer alert.

#### Scenario: Length excludes description

- **WHEN** an alert record declares zero or one payload byte but more bytes follow
- **THEN** the classifier does not read a peer alert description from outside that record

### Requirement: REQ-DPI38-F7 — Runtime experiment expiry

The runtime hint getter MUST expire an abandoned experiment at its configured TTL without recording a success or failure. TTL zero MUST retain its documented disabled meaning.

#### Scenario: Same context after expiry

- **WHEN** the same context requests hints after an unreported experiment expires
- **THEN** a new attempt can start and stale feedback cannot train it

### Requirement: REQ-DPI38-F8 — Typed bounded timeout

A timeout from a known blocking probe socket MUST remain distinguishable from nonblocking unreadiness and owner cancellation on supported operating systems.

#### Scenario: Unix timeout errno

- **WHEN** a bounded blocking read returns WouldBlock after its receive deadline
- **THEN** the probe reports timeout rather than an unspecified local read failure

### Requirement: REQ-DPI38-F9 — Neutral closure outcome

Closure and user cancellation before ServerHello MUST preserve forwarded bytes without success or failure reward and without a completed-handshake metric.

#### Scenario: User cancels handshake

- **WHEN** a ClientHello receives only user_canceled or close_notify and then closes
- **THEN** the result is neutral and no successful-handshake learning is recorded

### Requirement: REQ-DPI38-SCOPE — Compatibility and safety boundaries

The change MUST preserve existing JNI, JSON, protobuf, storage and configured activation contracts. It MUST NOT add dependencies, external services, secret logging or required root access.

#### Scenario: Existing rootless configuration

- **WHEN** an existing valid rootless configuration starts with evolution disabled
- **THEN** its ordinary planner remains available without new credentials or backend requirements

