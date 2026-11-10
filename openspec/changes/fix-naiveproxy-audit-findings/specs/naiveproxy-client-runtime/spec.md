## ADDED Requirements

### Requirement: REQ-RLY-1794300094981437-001 — Keep the resolver preference order

The implementation MUST preserve the encrypted resolver order when it selects a valid address for the Chromium host mapping. An IPv4 address MUST NOT override an earlier IPv6 address.

#### Scenario: Reachable IPv6 precedes unreachable IPv4

- **WHEN** the resolver returns a reachable IPv6 address before an unreachable IPv4 address
- **THEN** the Chromium MAP rule uses the IPv6 address and keeps the proxy name for TLS verification

#### Scenario: Invalid entries precede a valid address

- **WHEN** a result contains invalid entries before a valid address
- **THEN** the launcher ignores invalid entries without changing the valid entries order

### Requirement: REQ-RLY-1794300094981437-002 — Proxy failures degrade current health

The implementation MUST classify known proxy-side TLS, CONNECT, HTTP/2, and QUIC failures. Such a failure MUST change healthy relay telemetry to degraded until positive upstream evidence is observed. Destination-side refusal MUST NOT become a proxy failure. Recorded messages MUST contain no destination or credential.

#### Scenario: Proxy fails after success

- **WHEN** an upstream success is followed by a proxy certificate or connection error
- **THEN** health is degraded with the correct failure class

#### Scenario: Upstream recovers

- **WHEN** positive upstream evidence follows a classified failure
- **THEN** health becomes healthy and the old failure is cleared

### Requirement: REQ-RLY-1794300094981437-003 — Refuse invalid Basic credentials

Import, editor, service, and Rust helper validation MUST reject a Basic username containing a colon or an ASCII control, and a password containing an ASCII control. Controls include DEL. A supplied username MUST be nonblank. The helper mode with no upstream credentials MUST remain available. An empty password, spaces in valid credentials, Unicode text, and a colon in a password MUST remain valid. Validation messages MUST NOT disclose credentials. No stored schema change is required.

#### Scenario: Encoded colon in a username

- **WHEN** a NaiveProxy URI decodes to a username containing a colon
- **THEN** import rejects the URI before any upstream request

#### Scenario: Restored invalid credentials

- **WHEN** restored credentials contain a colon in the username or an ASCII control in either field
- **THEN** service validation refuses launch and the helper also rejects direct invalid stdin

#### Scenario: Valid empty password and password colon

- **WHEN** the username is valid and the password is empty or contains a colon
- **THEN** validation preserves the exact credentials
