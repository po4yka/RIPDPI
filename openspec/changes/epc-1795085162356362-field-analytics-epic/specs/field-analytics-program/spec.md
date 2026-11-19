## Purpose

Program-level invariants for opt-in, anonymous, aggregated field analytics so that every child change keeps consent, anonymity, and contract ownership consistent.

## ADDED Requirements

### Requirement: REQ-EPC-1795085162356362-001 — Consent gates all uploads

The implementation MUST send no field analytics unless the user has enabled the opt-in setting, which is off by default.

#### Scenario: Default install sends nothing

- **WHEN** a fresh install runs a tunnel session with healthy and failing connections
- **THEN** no request is made to any `field_report.endpoint`

### Requirement: REQ-EPC-1795085162356362-002 — Reports stay anonymous and aggregated

The implementation MUST emit only `client-field-report` v1 fields and MUST NOT include IP addresses, device or user identifiers, subscription tokens, hostnames other than `server_ref`, or timestamps finer than one hour.

#### Scenario: Forbidden field is rejected before upload

- **WHEN** a report candidate contains a field outside the v1 schema
- **THEN** the report is dropped and nothing is uploaded

### Requirement: REQ-EPC-1795085162356362-003 — Contracts have one owner

The implementation MUST consume `client-field-report` v1 and `ripdpi-bundle` v3 only through copies vendored from `ripdpi-vpn-deploy` with a drift pin, and MUST reject `ripdpi-bundle` v2.

#### Scenario: Drifted vendored contract fails the build

- **WHEN** the vendored contract version differs from the parser's supported version
- **THEN** the contract test fails

### Requirement: REQ-EPC-1795085162356362-004 — Child changes complete in dependency order

The implementation MUST land R1 and R2 before R3 and R4 are accepted, and R3 MUST NOT close before R2 and R4 are done.

#### Scenario: Blocked child cannot start early

- **WHEN** `./taskctl ready` is run while R2 is not done
- **THEN** R3 and R4 are not in the ready frontier
