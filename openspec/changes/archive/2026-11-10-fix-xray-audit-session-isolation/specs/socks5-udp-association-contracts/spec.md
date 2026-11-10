## Purpose

Keep local diagnostic UDP probes compatible with authenticated managed Xray sessions without weakening external SOCKS support.

## ADDED Requirements

### Requirement: REQ-UDP-LOCAL-SESSION — Negotiate authenticated local UDP authority

An authenticated loopback diagnostic SOCKS client MUST prebind its UDP source and attempt the existing RUF1 association before sending payload. It MUST validate session response frames and correlate error frames with the requested destination. Only an explicit unsupported-command reply may select standard SOCKS fallback on a new control connection. External or unauthenticated standard SOCKS behavior MUST remain available.

#### Scenario: Managed Xray peer

- **WHEN** a local authenticated peer accepts the private command
- **THEN** the diagnostic client sends session-wrapped requests and accepts only correctly wrapped responses from its relay.

#### Scenario: Existing standard peer

- **WHEN** a local peer explicitly rejects the private command as unsupported
- **THEN** the client starts a new authenticated standard association; malformed or denied private replies do not downgrade.
