## Purpose

Network probe verdicts must reflect valid QUIC and STUN exchanges, so malformed local packets or unrelated UDP traffic cannot appear to show a blocked or healthy path.

## ADDED Requirements

### Requirement: REQ-QUIC-VALID-INITIAL — Send valid QUIC Initial packets

The DPI suite MUST use a protocol-valid QUIC Initial packet for every selected target, including bundled targets.

#### Scenario: Bundled Cloudflare target

- **GIVEN** the DPI suite selects its bundled `cloudflare.com` target
- **WHEN** it sends a QUIC v1 fingerprint probe
- **THEN** it uses a valid native QUIC Initial packet instead of a synthetic fixture

### Requirement: REQ-QUIC-BUILD-FAILURE — Report local packet failure separately

The DPI suite MUST NOT classify a local QUIC packet-generation failure as network blocking.

#### Scenario: Native packet builder fails

- **GIVEN** the native builder cannot produce a QUIC Initial packet
- **WHEN** the DPI suite starts that probe
- **THEN** it reports a probe failure without sending a synthetic substitute or a blocked-path verdict

### Requirement: REQ-STUN-RESPONSE-MATCH — Verify STUN binding success

The Snowflake STUN probe MUST count reachability only for a binding success response from the requested endpoint that contains the STUN magic cookie and the request transaction ID.

#### Scenario: Matching response

- **GIVEN** a STUN binding request was sent to the configured endpoint
- **WHEN** a complete binding success response arrives from that endpoint with the matching transaction ID
- **THEN** the probe reports the STUN leg as available

#### Scenario: Invalid response

- **GIVEN** a STUN binding request was sent
- **WHEN** a datagram is truncated, has another message type, magic cookie, transaction ID, or source endpoint
- **THEN** the probe does not report the STUN leg as available
