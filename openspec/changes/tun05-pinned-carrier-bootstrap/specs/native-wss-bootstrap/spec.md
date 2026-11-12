## Purpose

Separate explicit underlay dial candidates from the authenticated WSS URL identity during native WireGuard carrier setup.

## ADDED Requirements

### Requirement: REQ-WSS-PINNED-DIAL — Explicit candidates own dialing

An explicit candidate list MUST contain only numeric IP addresses and MUST NOT use hostname resolution. Empty or invalid lists MUST fail before networking.

#### Scenario: Invalid explicit bootstrap

- **WHEN** an explicit list is empty or contains a hostname
- **THEN** setup returns InvalidInput without opening a connection

### Requirement: REQ-WSS-URL-IDENTITY — Preserve the URL identity

Numeric dial candidates MUST use the URL port while TLS identity, Host, path and query remain URL-derived.

#### Scenario: Domain URL and numeric candidate

- **WHEN** the URL hostname differs from the numeric dial candidate
- **THEN** TCP reaches the candidate and TLS uses the URL hostname
