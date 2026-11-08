# shared-proxy-listener Specification

## Purpose
Define how the proxy selects a protocol, bounds address resolution, authenticates CONNECT requests, and reports refusals on a shared TCP port.

## Requirements

### Requirement: REQ-LOC06-F1 — Bound handshake name resolution

Cancel DNS on shutdown or the handshake deadline. A late DNS answer SHALL NOT start an upstream connection.

#### Scenario: Bound handshake name resolution

- **GIVEN** a lookup stalls during handshake
- **WHEN** the deadline expires or the runtime stops
- **THEN** the lookup ends and the worker releases its slot without opening an upstream connection.

### Requirement: REQ-LOC06-F2 — Reject terminal address errors

An address listener MUST resolve a complete domain at most once and reject unusable address types without waiting for payload.

#### Scenario: Reject terminal address errors

- **GIVEN** an address is invalid or cannot resolve
- **WHEN** the peer keeps the stream open
- **THEN** the listener closes without a second lookup.

### Requirement: REQ-LOC06-F3 — Use one HTTP field boundary

HTTP admission MUST read credentials only from the valid header section.

#### Scenario: Use one HTTP field boundary

- **GIVEN** a valid credential follows an empty LF line
- **WHEN** a client requests CONNECT
- **THEN** the listener denies the request and does not connect.

### Requirement: REQ-LOC06-F4 — Reject malformed HTTP field names

HTTP CONNECT MUST reject whitespace before the field colon with 400.

#### Scenario: Reject malformed HTTP field names

- **GIVEN** a field has whitespace before its colon
- **WHEN** a client sends CONNECT
- **THEN** the listener returns 400 without connecting.

### Requirement: REQ-LOC06-F5 — Report HTTP header overflow

HTTP CONNECT MUST return a readable 4xx when fields exceed the 64 KiB limit.

#### Scenario: Report HTTP header overflow

- **GIVEN** a client sends a header block
- **WHEN** the header ends at byte 65536 or 65537
- **THEN** the former can connect and the latter receives a client error.

### Requirement: REQ-LOC06-F6 — Report policy rejection

HTTP CONNECT MUST distinguish local routing denial from an upstream failure.

#### Scenario: Report policy rejection

- **GIVEN** a target is the listener or a blocked destination
- **WHEN** the client requests CONNECT
- **THEN** the listener returns 403 without an upstream connection.

### Requirement: REQ-LOC06-F7 — Keep the advertised port accurate

The UI MUST describe the effective configured proxy port behavior and preserve an explicit port when enabling Mixed.

#### Scenario: Keep the advertised port accurate

- **GIVEN** stock settings use port 1080 or the user selected a port
- **WHEN** the user enables Mixed
- **THEN** the settings and the proxy configuration agree and the port is preserved.

### Requirement: REQ-LOC06-F8 — Expose listener options in CLI help

The debug CLI MUST list Mixed, HTTP-only and Transparent in help.

#### Scenario: Expose listener options in CLI help

- **GIVEN** the debug client is available
- **WHEN** the operator requests help
- **THEN** all three listener switches and their roles are shown.
