## MODIFIED Requirements

### Requirement: REQ-MIRROR-BYTE-IDENTITY — Frozen producer identity

The repository MUST store every observability and network-exposure contract
byte-for-byte equal to the files at one declared frozen deployment producer
revision.

#### Scenario: Exact schema 3 mirror

- **WHEN** the deployment contract at the frozen producer revision is compared
  with RIPDPI's existing vendored probe-matrix report schema
- **THEN** the files have identical bytes and the vendored document remains a
  valid JSON Schema describing schema version 3

#### Scenario: Producer mismatch

- **WHEN** any vendored contract differs by any byte from the frozen producer
  contract
- **THEN** contract mirror validation fails instead of accepting the stale or
  independently edited client copy

#### Scenario: Exact combined mirror

- **WHEN** the seven vendored files are compared with the frozen producer
- **THEN** every file has identical bytes and parses as JSON

#### Scenario: Missing or modified mirror

- **WHEN** any producer file is absent or differs by any byte
- **THEN** contract mirror validation fails instead of accepting a partial or
  independently edited client contract set

### Requirement: REQ-MIRROR-SCOPE-ISOLATION — Contract-resource-only change

The implementation MUST NOT add client runtime consumers or claim telemetry,
alerting, firewall, or network-enforcement behavior.

#### Scenario: Runtime boundary review

- **WHEN** the combined mirror diff is reviewed
- **THEN** Kotlin, Rust, Android resources, and existing contract files remain
  unchanged

#### Scenario: Hosted validation boundary

- **WHEN** local mirror and repository checks pass
- **THEN** delivery remains pending until required hosted checks pass on the
  exact published client commit
