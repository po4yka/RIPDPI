## Purpose

Extend the deployment contract mirror so RIPDPI rejects stale
protocol-liveness policy schemas while keeping schema-only synchronization
isolated from application runtime behavior.

## MODIFIED Requirements

### Requirement: REQ-MIRROR-BYTE-IDENTITY — Frozen producer identity

The repository MUST store every deployment-owned vendored contract, including
the protocol-liveness policy schema, byte-for-byte equal to the contract at the
declared frozen producer revision.

#### Scenario: Exact schema 3 mirror

- **WHEN** the deployment contract at the frozen producer revision is compared
  with RIPDPI's vendored probe-matrix report schema
- **THEN** the files have identical bytes and the vendored document is valid
  JSON Schema describing schema version 3

#### Scenario: Exact protocol-liveness schema 2 mirror

- **WHEN** the deployment contract at producer revision
  `08cd71efd309f893d3fa210bd4560d96bf799742` is compared with RIPDPI's vendored
  protocol-liveness schema
- **THEN** the files have identical bytes and the vendored document is valid
  JSON Schema describing schema version 2

#### Scenario: Producer mismatch

- **WHEN** any of the 22 vendored contract files differs by any byte from the
  frozen producer contract set
- **THEN** contract mirror validation fails instead of accepting a stale or
  independently edited client copy

#### Scenario: Legacy policy document

- **WHEN** a schema 1 protocol-liveness policy without the exact sentinel
  target binding is checked against the vendored schema 2 contract
- **THEN** validation rejects the policy as migration-required

### Requirement: REQ-MIRROR-SCOPE-ISOLATION — Schema-only client change

The implementation MUST NOT change client runtime parsing, schema 2 probe
matrix window semantics, network-exposure contracts, or device behavior as
part of the protocol-liveness mirror update.

#### Scenario: Runtime boundary review

- **WHEN** the protocol-liveness schema 2 mirror change is reviewed
- **THEN** Kotlin and Rust runtime sources remain unchanged and the only
  contract payload change is the vendored protocol-liveness schema

#### Scenario: Hosted validation boundary

- **WHEN** local mirror, task, OpenSpec, core data, architecture, and configured
  hook checks pass
- **THEN** the change remains pending until required hosted checks pass on the
  exact published client commit
