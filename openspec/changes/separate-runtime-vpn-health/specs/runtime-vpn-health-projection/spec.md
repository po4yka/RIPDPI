## Purpose

Expose local runtime readiness and VPN data-plane validation as independent facts so a started process stack is never presented as proof that VPN traffic works.

## ADDED Requirements

### Requirement: REQ-RUNTIME-VPN-HEALTH-001 — Separate lifecycle and data-plane state

The implementation MUST preserve the local service lifecycle state independently from a typed VPN data-plane status.

#### Scenario: Local runtime starts before VPN validation

- **WHEN** the VPN service reports a running local runtime and current VPN path validation is still incomplete
- **THEN** the application MUST expose the local runtime as active and the VPN data plane as checking rather than working

### Requirement: REQ-RUNTIME-VPN-HEALTH-002 — Require positive VPN path evidence

The implementation MUST report the VPN data plane as working only when captured evidence identifies a present VPN path with Internet capability, Android validation, and no captive portal.

#### Scenario: Captured VPN path is validated

- **WHEN** the local VPN runtime is active and captured path evidence satisfies every positive validation condition
- **THEN** the Home presentation MAY report the VPN connection as working

#### Scenario: Captured VPN path is unvalidated

- **WHEN** the local VPN runtime is active but captured evidence reports an absent, non-Internet, unvalidated, or captive-portal VPN path
- **THEN** the Home presentation MUST state that local processes are active while VPN connectivity is unavailable and MUST NOT present a locked working connection

### Requirement: REQ-RUNTIME-VPN-HEALTH-003 — Remain neutral without applicable evidence

The implementation MUST avoid inferring VPN failure or success when VPN validation is not applicable or cannot be captured.

#### Scenario: Proxy mode is active

- **WHEN** the active local runtime uses proxy mode
- **THEN** VPN data-plane status MUST be not applicable and existing proxy presentation MUST remain unchanged

#### Scenario: Path evidence cannot be captured

- **WHEN** the local VPN runtime is active but path evidence is unavailable because the platform capture is unavailable
- **THEN** the application MUST report VPN connectivity as unverified rather than working or failed

### Requirement: REQ-RUNTIME-VPN-HEALTH-004 — Preserve privacy and compatibility boundaries

The implementation MUST derive the status only from the existing coarse path evidence and MUST NOT add network identifiers, native wire fields, protobuf fields, or persisted migrations.

#### Scenario: Health projection is evaluated

- **WHEN** Home derives VPN data-plane status
- **THEN** it MUST consume only lifecycle mode and coarse Boolean path capabilities already available in `NetworkPathValidationEvidence`
