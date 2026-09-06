## Purpose

Keep every deployment-owned contract mirror byte-identical to its declared
frozen producer revision while keeping contract synchronization isolated from
client runtime behavior.

## MODIFIED Requirements

### Requirement: REQ-MIRROR-BYTE-IDENTITY — Frozen producer identity

The repository MUST store every deployment-owned vendored contract
byte-for-byte equal to its task-declared frozen producer revision. Different
mirror tasks MAY pin different producer revisions, but each mirror MUST name
and validate its exact revision.

#### Scenario: Exact schema 3 mirror

- **WHEN** the deployment contract at the frozen producer revision is compared
  with RIPDPI's vendored probe-matrix report schema
- **THEN** the files have identical bytes and the vendored document is valid
  JSON Schema describing schema version 3

#### Scenario: Exact protocol-liveness schema 2 mirror

- **WHEN** the deployment contract at producer revision
  `8396ec8c954eda64ae4b78dc1c8f2d18de207c3b` is compared with RIPDPI's vendored
  protocol-liveness schema
- **THEN** the files have identical bytes and the vendored document is valid
  JSON Schema describing schema version 2

#### Scenario: Legacy policy document

- **WHEN** a schema 1 protocol-liveness policy without the exact sentinel
  target binding is checked against the vendored schema 2 contract
- **THEN** validation rejects the policy as migration-required

#### Scenario: Exact observability and network-exposure mirror

- **WHEN** the seven task-owned contract files are compared with producer
  revision `7d176401777eb7d5c1062d2dab94a725286bf8ec`
- **THEN** every file has identical bytes and parses as JSON

#### Scenario: Exact evidence schema 4 mirror

- **WHEN** the real-VPS AWG NAT evidence schema at producer revision
  `c8ad0861711eb5fb63c6fad46c28c179678d51a5` is compared with the vendored
  client resource
- **THEN** both files have identical bytes, the client document parses as JSON,
  and its `version` constant is `real_vps_awg_nat_evidence_v4`

#### Scenario: Producer mismatch

- **WHEN** any task-owned producer file is absent or its vendored copy differs
  by any byte from the declared frozen revision
- **THEN** contract mirror validation fails instead of accepting a missing,
  stale, reformatted, or independently edited client contract

### Requirement: REQ-MIRROR-SCOPE-ISOLATION — Contract-resource-only change

A deployment-contract mirror change MUST limit client changes to vendored
contract resources and its own task/OpenSpec records. It MUST NOT add or change
Kotlin or Rust runtime consumers, Android resources, signer or relay behavior,
device behavior, telemetry delivery, alerting, firewall enforcement, or network
enforcement unless a separate task and specification explicitly own that work.

#### Scenario: Runtime boundary review

- **WHEN** a deployment-contract mirror change is reviewed
- **THEN** application and native runtime sources remain unchanged and only the
  task-owned vendored contract payloads and task/OpenSpec records differ

#### Scenario: Evidence-schema boundary review

- **WHEN** the real-VPS AWG NAT evidence-schema mirror is reviewed
- **THEN** it does not add signer, relay, runtime, device, artifact, or
  deployment behavior

#### Scenario: Hosted validation boundary

- **WHEN** local mirror, task, OpenSpec, architecture, and applicable contract
  checks pass
- **THEN** completion remains pending until required hosted checks pass for the
  exact published client commit
