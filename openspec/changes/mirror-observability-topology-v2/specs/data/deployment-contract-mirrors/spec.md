## Purpose

Synchronize the bounded observability topology contract without introducing
runtime observability behavior into the offline-first client.

## ADDED Requirements

### Requirement: REQ-TOPOLOGY-V2-MIRROR — Exact topology v2 mirror

The client MUST store the observability topology schema byte-for-byte equal to
producer revision `c7ef9bd519c28841f0c0b74256692fece1aa692a` and MUST preserve all
other mirrored contracts.

#### Scenario: Exact bounded topology contract

- **WHEN** the full client mirror is compared with the frozen producer contracts
- **THEN** every file matches, no file is missing or orphaned, and the topology
  schema requires version 2, observer metadata, and node capabilities

#### Scenario: Stale or independently edited contract

- **WHEN** any client contract differs from the frozen producer
- **THEN** byte-identity validation fails without accepting a compatibility shim

### Requirement: REQ-TOPOLOGY-V2-ISOLATION — Test-resource-only publication

This update MUST NOT change Kotlin/Rust runtime code, application configuration,
credentials, or device behavior, and MUST pass required checks before protected
main integration.

#### Scenario: Scope and acceptance review

- **WHEN** the authorized topology mirror PR is integrated
- **THEN** the only contract payload change is the topology schema and exact-head
  hosted checks have passed; local checks alone do not count as integration
