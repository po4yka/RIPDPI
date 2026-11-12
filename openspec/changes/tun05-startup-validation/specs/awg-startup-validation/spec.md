## Purpose
Keep AWG readiness and endpoint validation consistent across Kotlin callers.

## ADDED Requirements

### Requirement: REQ-AWG-DEADLINE — Allow carrier setup
WS readiness MUST allow 40 seconds; UDP readiness MUST retain 15 seconds.

#### Scenario: Slow valid setup
- **WHEN** WS setup takes 10 seconds and peer authentication takes 6 seconds
- **THEN** readiness accepts the authenticated ready signal.

### Requirement: REQ-AWG-URL — Validate WSS identity
The editor and request MUST reject non-WSS URLs, missing hosts, userinfo and fragments.

#### Scenario: Invalid endpoint
- **WHEN** a WS profile contains a non-WSS URL
- **THEN** validation fails before activation and does not include the URL in the error.

#### Scenario: Valid endpoint
- **WHEN** a WSS URL includes a domain or IPv6 host, port, path and query
- **THEN** validation accepts it.
