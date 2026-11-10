# relay/cloudflare-publish-safety Specification

## Purpose
Provide correct streaming and safe validation for the existing Cloudflare publish relay path.

## Requirements

### Requirement: REQ-RLY-1794300683762457-001 — Streaming upload lifetime

The origin MUST keep a successful POST response open while its upload is active and MUST release the upload when the response or session closes.

#### Scenario: Streaming upload lifetime

- **WHEN** a client sends a valid VLESS header and payload in delayed chunks
- **THEN** all chunks reach the TCP destination without early response EOF, and cancellation closes owned resources

### Requirement: REQ-RLY-1794300683762457-002 — Credential error privacy

The publisher MUST report malformed credentials without credential input in messages, causes, or suppressed exceptions.

#### Scenario: Credential error privacy

- **WHEN** credentials contain a secret and malformed JSON or an invalid field type
- **THEN** the reported exception is safe to log and contains no secret

### Requirement: REQ-RLY-1794300683762457-003 — xHTTP query compatibility

The origin MUST match the configured path component independently from its query.

#### Scenario: xHTTP query compatibility

- **WHEN** a client uses /xhttp?key=value and places the session before the query
- **THEN** the origin accepts the same path and session as /xhttp

### Requirement: REQ-RLY-1794300683762457-004 — TCP-only command dispatch

The origin MUST reject UDP and Mux VLESS commands before authentication callbacks or destination socket creation.

#### Scenario: TCP-only command dispatch

- **WHEN** a peer with the correct identity sends command 2 or 3
- **THEN** the request fails explicitly and no TCP connection is made

### Requirement: REQ-RLY-1794300683762457-005 — Loopback URL validation

The publisher MUST accept IPv4, localhost, and bracketed IPv6 loopback HTTP URLs with ports 1 through 65535 and MUST reject other hosts or invalid ports.

#### Scenario: Loopback URL validation

- **WHEN** the configured origin is http://[::1]:43128
- **THEN** validation returns an IPv6 loopback listener and retains correct URL brackets

### Requirement: REQ-RLY-1794300683762457-006 — Editor identity validation

The editor MUST require a nonempty VLESS identity in consume and publish modes without narrowing the existing accepted identity formats.

#### Scenario: Editor identity validation

- **WHEN** a publish draft has an origin and tunnel credentials but no VLESS identity
- **THEN** the editor reports the identity field as required before saving
