## Purpose

Measure actual HTTP/3 request and response progress without treating QUIC Initial replies as application success.

## ADDED Requirements

### Requirement: REQ-H3-PROTOCOL — Validate HTTP3 exchange

The probe MUST verify the TLS certificate and hostname, negotiate h3, send GET and decode HTTP/3 response headers before it reports HTTP/3 validation. It MUST NOT fall back to TCP or another HTTP version.

#### Scenario: Real response

- **WHEN** a trusted local H3 server returns headers, DATA and FIN
- **THEN** the report records negotiated h3, validated TLS, status, byte count and body completion.

#### Scenario: Transport or certificate failure

- **WHEN** only QUIC Initial/VN is received, certificate verification fails, or h3 is not negotiated
- **THEN** the report does not claim HTTP/3 validation.

### Requirement: REQ-H3-BOUNDS — Bound and protect network work

The probe MUST use protected UDP sockets, a single absolute deadline, bounded DNS/address attempts, response header and body limits, and cooperative cancellation. Unsupported proxy paths MUST produce explicit unsupported evidence without a direct connection.

#### Scenario: Interrupted body

- **WHEN** a peer sends headers then stalls, cancellation arrives, or the byte cap is reached
- **THEN** the report retains observed status and bytes, marks the body incomplete and releases protocol resources.

### Requirement: REQ-H3-PRESENT — Preserve independent facts

The app MUST preserve HTTP status, protocol confirmation, failure stage and body progress separately in current results, history, stage scale and summaries. It MUST not classify HTTP 4xx/5xx as an unavailable H3 protocol. Initial-only results MUST keep their old limited semantics.

#### Scenario: HTTP error

- **WHEN** a trusted H3 peer returns HTTP 403 with a complete body
- **THEN** HTTP/3 is confirmed and the HTTP error is shown separately.

### Requirement: REQ-H3-PRIVACY — Bound exported evidence and revoke stale authority

The app MUST reject invalid metadata, remove peer addresses and unknown/free text from redacted evidence, and revoke positive network authority after a network change. Response bodies and response headers MUST NOT be persisted.

#### Scenario: Changed network

- **WHEN** a completed H3 result belongs to a network invalidated during finalization
- **THEN** saved and current results become inconclusive and cannot imply current reachability.
