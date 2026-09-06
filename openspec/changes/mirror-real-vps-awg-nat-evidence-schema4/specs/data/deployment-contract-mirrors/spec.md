## Purpose

Keep the producer-owned real-VPS AWG NAT evidence schema available to client
contract validation while preserving its byte identity and runtime isolation.

## MODIFIED Requirements

### Requirement: REQ-MIRROR-BYTE-IDENTITY — Frozen producer identity

The repository MUST store the real-VPS AWG NAT evidence schema byte-for-byte
equal to `contract/real-vps-awg-nat-evidence.schema.json` at producer revision
`c8ad0861711eb5fb63c6fad46c28c179678d51a5`.

#### Scenario: Exact schema 3 mirror

- **WHEN** the deployment contract at the frozen producer revision is compared
  with RIPDPI's vendored probe-matrix report schema
- **THEN** the files have identical bytes and the vendored document is valid
  JSON Schema describing schema version 3

#### Scenario: Exact evidence schema 4 mirror

- **GIVEN** the frozen producer revision is available
- **WHEN** its evidence schema is compared with the vendored client resource
- **THEN** both files have identical bytes, the client document parses as JSON,
  and its `version` constant is `real_vps_awg_nat_evidence_v4`

#### Scenario: Producer mismatch

- **GIVEN** a vendored evidence schema differs by any byte from the frozen
  producer file
- **WHEN** the focused mirror check runs
- **THEN** it fails rather than accepting reformatted, stale, or partial bytes

### Requirement: REQ-MIRROR-SCOPE-ISOLATION — Schema-only client change

The implementation MUST add only the vendored evidence-schema resource and
its task/OpenSpec verification records; it MUST NOT add signer, relay, runtime,
device, or deployment behavior.

#### Scenario: Runtime boundary review

- **GIVEN** the mirror change is reviewed
- **WHEN** changed source paths are examined
- **THEN** Kotlin and Rust runtime sources, Android resources, signer code,
  relay code, and device tests remain unchanged

#### Scenario: Hosted validation boundary

- **GIVEN** local mirror, task, OpenSpec, and architecture checks pass
- **WHEN** the change is ready for protected integration
- **THEN** completion remains pending until required hosted checks pass for the
  exact published client commit
