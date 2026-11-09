## Purpose

Preserve user payload bytes while applying the existing plaintext HTTP request strategies safely.

## ADDED Requirements

### Requirement: REQ-HTTP-MUT-001 — Preserve opaque stream bytes

The implementation MUST apply HTTP mutations only to a complete first HTTP/1 request head. It MUST preserve body bytes, later stream bytes, and HTTP/2 data.

#### Scenario: Preserve opaque stream bytes

- **WHEN** a POST body or H2 DATA slice looks like an HTTP request
- **THEN** the bytes remain identical, regardless of TCP read boundaries.

### Requirement: REQ-HTTP-MUT-002 — Reject incomplete reconstruction

The implementation MUST NOT synthesize an end of headers for an incomplete HTTP request. Existing time and size bounds MUST remain effective.

#### Scenario: Reject incomplete reconstruction

- **WHEN** a partial head ends after Content-Length and a later chunk contains another header and DATA
- **THEN** the header stays a header and the body remains DATA.

### Requirement: REQ-HTTP-MUT-003 — Select the first head boundary

The implementation MUST bound inspection at the first supported empty line and MUST NOT find Host in the body.

#### Scenario: Select the first head boundary

- **WHEN** an LF head precedes a body with CRLFCRLF and a Host-looking line
- **THEN** no body byte is treated as a header or changed.

### Requirement: REQ-HTTP-MUT-004 — Use unambiguous authority metadata

The implementation MUST use absolute-form or CONNECT request-target authority for decision metadata. It MUST suppress ambiguous Host fields instead of returning an arbitrary first Host.

#### Scenario: Use unambiguous authority metadata

- **WHEN** the request target names real.example and Host names decoy.example, or multiple Host fields occur
- **THEN** metadata identifies the target authority or returns an explicit unavailable result for ambiguity.

### Requirement: REQ-HTTP-MUT-005 — Avoid bare CR in builtin method EOL

The builtin method EOL strategy MUST NOT generate a bare CR inside the request line and MUST preserve the body.

#### Scenario: Avoid bare CR in builtin method EOL

- **WHEN** a supported HTTP/1 request including TRACE is modified
- **THEN** the request line stays intact and any added empty line precedes the method.
