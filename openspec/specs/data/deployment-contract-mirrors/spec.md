# data/deployment-contract-mirrors Specification

## Purpose
Define how RIPDPI keeps deployment-owned evidence contracts synchronized
without expanding a schema-only mirror into application runtime behavior.

## Requirements

### Requirement: REQ-MIRROR-BYTE-IDENTITY — Frozen producer identity

The repository MUST store the probe-matrix report schema byte-for-byte equal
to the contract at the declared frozen producer revision.

#### Scenario: Exact schema 3 mirror

- **WHEN** the deployment contract at the frozen producer revision is compared
  with RIPDPI's vendored probe-matrix report schema
- **THEN** the files have identical bytes and the vendored document is valid
  JSON Schema describing schema version 3

#### Scenario: Producer mismatch

- **WHEN** the vendored schema differs by any byte from the frozen producer
  contract
- **THEN** the contract mirror validation fails instead of accepting the stale
  or independently edited client copy

### Requirement: REQ-MIRROR-SCOPE-ISOLATION — Schema-only client change

The implementation MUST NOT change client runtime report parsing, schema 2
window semantics, or network-exposure contracts as part of this mirror update.

#### Scenario: Runtime boundary review

- **WHEN** the schema 3 mirror change is reviewed
- **THEN** the application and native runtime sources remain unchanged and the
  only contract payload change is the vendored probe-matrix report schema

#### Scenario: Hosted validation boundary

- **WHEN** local mirror, task, OpenSpec, and architecture checks pass
- **THEN** the change remains pending until required hosted checks pass on the
  exact published client commit
