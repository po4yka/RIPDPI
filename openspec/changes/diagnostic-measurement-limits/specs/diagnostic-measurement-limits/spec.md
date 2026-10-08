## Purpose

Keep diagnostic evidence, matching controls, and limits visible in local and shared reports.

## ADDED Requirements

### Requirement: REQ-DIAGNOSTIC-LIMITS — Preserve evidence boundaries

The application MUST show matching control state and applicable measurement limits without asserting a provider cause.

#### Scenario: Missing matching control

- **WHEN** a diagnosis has no matching control result
- **THEN** the report states that control validation is not established

#### Scenario: QUIC initial response

- **WHEN** a QUIC connectivity result is shown
- **THEN** its boundary states that a complete HTTP/3 request was not tested

### Requirement: REQ-INTERFACE-MTU — Distinguish interface and path

The application MUST label the captured MTU as interface MTU and state that path MTU is not measured.

#### Scenario: Interface value is available

- **WHEN** a network snapshot includes an MTU
- **THEN** the value does not imply a measured path MTU or blackhole result

### Requirement: REQ-UNKNOWN-EXPORT — Preserve unavailable facts

The application MUST preserve unknown network facts in exported summaries.

#### Scenario: Legacy snapshot lacks availability

- **WHEN** a snapshot has no capability availability metadata
- **THEN** validation and captive portal state remain unknown
