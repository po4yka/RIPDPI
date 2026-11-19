## Purpose

Define how the client parses ripdpi-bundle v3, rejects v2, and uses per-endpoint health to order failover candidates.

## ADDED Requirements

### Requirement: REQ-DAT-1795085420746769-001 — Parse bundle v3

The implementation MUST parse a v3 bundle including each endpoint's `health` and the top-level `field_report` block, and MUST expose endpoint ids and `field_report` in the parse result.

#### Scenario: Golden v3 bundle

- **WHEN** the vendored v3 example bundle is parsed
- **THEN** each endpoint carries its health status, observed_at, and ttl_seconds, and `field_report` carries endpoint, cohort_token, and max_reports_per_day

#### Scenario: Invalid field_report endpoint

- **WHEN** `field_report.endpoint` is not an https URL
- **THEN** the bundle is rejected with a typed error naming the field

### Requirement: REQ-DAT-1795085420746769-002 — Reject v2

The implementation MUST reject a bundle whose contract version is 2 or any version other than 3, with a typed unsupported-version error.

#### Scenario: v2 bundle rejected

- **WHEN** a valid v2 bundle is parsed
- **THEN** the result is the unsupported-version error and no profile is created

### Requirement: REQ-DAT-1795085420746769-003 — Health drives failover ordering

The implementation MUST exclude endpoints with unexpired status `down`, MUST deprioritise `degraded`, and MUST treat `unknown` and expired health as neutral.

#### Scenario: Down endpoint is excluded

- **WHEN** endpoint A is healthy and endpoint B is down with an unexpired ttl
- **THEN** the failover candidate list contains A and not B

#### Scenario: Expired health is neutral

- **WHEN** endpoint B is down with observed_at plus ttl_seconds before the evaluation time
- **THEN** B is treated as unknown and keeps its neutral position

#### Scenario: Degraded is deprioritised

- **WHEN** endpoint A is degraded and endpoint B is unknown
- **THEN** B is ordered before A

#### Scenario: All endpoints down

- **WHEN** every endpoint is down and unexpired
- **THEN** the candidate list is empty and the typed outcome reports no usable endpoint

### Requirement: REQ-DAT-1795085420746769-004 — Vendored v3 contract is drift-pinned

The implementation MUST test against byte-identical vendored v3 schema and fixtures, and the tests MUST fail when the schema's `x-contract-version` differs from the parser's supported version.

#### Scenario: Version pin

- **WHEN** the vendored schema declares `x-contract-version` other than the parser constant
- **THEN** `RipdpiBundleContractTest` fails
