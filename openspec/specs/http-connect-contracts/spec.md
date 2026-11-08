# http-connect-contracts Specification

## Purpose
Define observable parsing and connection behavior for the local HTTP CONNECT listener.

## Requirements

### Requirement: REQ-LOC05-ORDER — Establish upstream before success

The implementation MUST send no successful CONNECT response before a direct or tunnel upstream is established.

#### Scenario: Establish upstream before success

- **WHEN** a delayed HTTP CONNECT targets a closed local port
- **THEN** the client receives 502 before any success and no second HTTP response follows a successful tunnel.

#### Scenario: HTTP WS bootstrap fails before classification

- **WHEN** HTTP CONNECT selects a known target-DC WebSocket and its bootstrap fails before the waiting client sends a seed
- **THEN** the listener returns 502 for a connection failure or 504 for a timeout before any success, without a speculative direct connection; seed fallback remains available after successful WS setup.

### Requirement: REQ-LOC05-FRAMING — Reject invalid CONNECT framing

The implementation MUST reject invalid Content-Length, conflicting lengths, Transfer-Encoding and request content framing before dial.

#### Scenario: Reject invalid CONNECT framing

- **WHEN** a CONNECT has conflicting Content-Length fields or Transfer-Encoding
- **THEN** the listener returns 400 and the upstream listener accepts no connection.

### Requirement: REQ-LOC05-FIELDS — Validate all HTTP field lines

The implementation MUST reject invalid field names, whitespace before a colon, dangerous control bytes, obsolete folding and ambiguous authorization fields.

#### Scenario: Validate all HTTP field lines

- **WHEN** a CONNECT has Host whitespace before the colon or a NUL in a field value
- **THEN** the listener returns 400 without opening an upstream.

### Requirement: REQ-LOC05-HTTP10 — Accept HTTP/1.0 authority without Host

The implementation MUST accept HTTP/1.0 CONNECT without Host while requiring exactly one valid Host for HTTP/1.1.

#### Scenario: Accept HTTP/1.0 authority without Host

- **WHEN** a client sends HTTP/1.0 CONNECT with a complete authority and no Host
- **THEN** the tunnel opens; the HTTP/1.1 equivalent fails with 400.

### Requirement: REQ-LOC05-OCTETS — Parse header values as octets

The implementation MUST accept legal obs-text in unknown field values without rejecting valid Basic credentials.

#### Scenario: Parse header values as octets

- **WHEN** a request has valid ripdpi token credentials and an unknown field containing byte FF
- **THEN** authentication succeeds and only tunnel bytes reach the upstream.

### Requirement: REQ-LOC05-PORT — Require decimal authority ports

The implementation MUST reject non-digit authority ports before numeric conversion.

#### Scenario: Require decimal authority ports

- **WHEN** an authority port starts with a plus sign
- **THEN** the request fails with 400 and no upstream opens.

### Requirement: REQ-LOC05-LIMIT — Report excessive request headers

The implementation MUST bound header buffering to 64 KiB and send an HTTP 4xx response when the limit is exceeded.

#### Scenario: Report excessive request headers

- **WHEN** a complete CONNECT head has 65537 bytes
- **THEN** the client receives 431 and closes; a legal 65536-byte head still works.
