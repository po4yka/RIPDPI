## Purpose

Define how the client parses ripdpi-bundle v3, rejects v2, and uses per-endpoint health to order failover candidates.

## ADDED Requirements

### Requirement: REQ-DAT-1795085420746769-001 — Parse bundle v3

The implementation MUST parse a v3 bundle including the `ripdpi.endpoints` health map keyed by `server_ref` and the top-level `field_report` block, and MUST expose the health by `server_ref` and `field_report` in the parse result.

#### Scenario: Golden v3 bundle

- **WHEN** the vendored v3 example and golden-full bundles are parsed
- **THEN** each `server_ref` in `ripdpi.endpoints` carries its health status, observed_at, and ttl_seconds, and `field_report` carries endpoint, cohort_token, and max_reports_per_day

#### Scenario: Invalid field_report endpoint

- **WHEN** `field_report.endpoint` is not an https URL
- **THEN** the bundle is rejected with a typed error naming the field

### Requirement: REQ-DAT-1795085420746769-002 — Reject v2

The implementation MUST reject a bundle whose contract version is 2 or any version other than 3, with a typed unsupported-version error.

#### Scenario: v2 bundle rejected

- **WHEN** a valid v2 bundle is parsed
- **THEN** the result is the unsupported-version error and no profile is created

### Requirement: REQ-DAT-1795085420746769-003 — Health drives failover ordering

The implementation MUST store every selector member with its health and evaluate the health at failover selection time. It MUST exclude endpoints with unexpired status `down`, MUST deprioritise `degraded`, and MUST treat `unknown` and expired health as neutral. When every endpoint is down it MUST fail open: keep all members as candidates in declared order and report a typed all-down outcome.

#### Scenario: Down endpoint is excluded

- **WHEN** endpoint A is healthy and endpoint B is down with an unexpired ttl
- **THEN** the failover candidate list contains A and not B

#### Scenario: Expired health is neutral

- **WHEN** endpoint B is down with observed_at plus ttl_seconds before the evaluation time
- **THEN** B is treated as unknown and keeps its neutral position

#### Scenario: Down health expires after import

- **WHEN** a group was imported while endpoint B was down and B's ttl expires before the next refresh
- **THEN** the next failover selection treats B as a neutral candidate

#### Scenario: Degraded is deprioritised

- **WHEN** endpoint A is degraded and endpoint B is unknown
- **THEN** B is ordered before A

#### Scenario: All endpoints down

- **WHEN** every endpoint is down and unexpired
- **THEN** the import succeeds, every member stays a candidate in declared order, and the typed outcome is `EndpointHealthOrder.AllDownFailOpen`

### Requirement: REQ-DAT-1795085420746769-004 — Vendored v3 contract is drift-pinned

The implementation MUST test against byte-identical vendored v3 schema and fixtures pinned by a SHA-256 manifest, and the tests MUST fail when the schema's `x-contract-version` differs from the parser's supported version or when a vendored file differs from, or is missing from, the manifest.

#### Scenario: Version pin

- **WHEN** the vendored schema declares `x-contract-version` other than the parser constant
- **THEN** `RipdpiBundleContractTest` fails

#### Scenario: Hash pin

- **WHEN** a vendored bundle or field-report file is edited, removed, or added without updating `vendored-deploy-contracts.sha256`, or the deployer `contract/` set differs from it
- **THEN** `scripts/ci/check_vendored_deploy_contracts.py` fails
