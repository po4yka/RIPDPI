## Purpose

Define the observable completion contract for Wire NaiveProxy helper probe into manager startup. The helper-side --probe line and Kotlin parser now exist. Finish the Android startup integration by invoking --probe before launch, rejecting unsupported schema versions, and documenting the enforced policy

## ADDED Requirements

### Requirement: REQ-SVC-1786264762917506-001 — (2026-05-15) Helper emits a single RIPDPI-PROBE { ... } JSON line on --probe ex…

The RIPDPI implementation MUST satisfy this portfolio criterion: (2026-05-15) Helper emits a single RIPDPI-PROBE { ... } JSON line on --probe exit with fields { "schemaversion": u32, "helperversion": semver, "features": [string, ...] }. Hand-formatted JSON (no serde dep for the fast-path) in ripdpi-naiveproxy/src/main.rs.….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that (2026-05-15) Helper emits a single RIPDPI-PROBE { ... } JSON line on --probe exit with fields { "schemaversion": u32, "helperversion": semver, "features": [string, ...] }. Hand-formatted JSON (no serde dep for the fast-path) in ripdpi-naiveproxy/src/main.rs.…

### Requirement: REQ-SVC-1786264762917506-002 — (2026-05-28) Kotlin parser exists in NaiveProxyProbeParser.kt, with unit tests…

The RIPDPI implementation MUST satisfy this portfolio criterion: (2026-05-28) Kotlin parser exists in NaiveProxyProbeParser.kt, with unit tests covering marker, malformed JSON, missing required fields, and schema-range checks.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that (2026-05-28) Kotlin parser exists in NaiveProxyProbeParser.kt, with unit tests covering marker, malformed JSON, missing required fields, and schema-range checks

### Requirement: REQ-SVC-1786264762917506-003 — NaiveProxyManager invokes --probe before start, parses the JSON, and refuses to…

The RIPDPI implementation MUST satisfy this portfolio criterion: NaiveProxyManager invokes --probe before start, parses the JSON, and refuses to start when schemaversion is outside the range it supports, surfacing a recognizable failure class.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that NaiveProxyManager invokes --probe before start, parses the JSON, and refuses to start when schemaversion is outside the range it supports, surfacing a recognizable failure class

### Requirement: REQ-SVC-1786264762917506-004 — Existing RIPDPI-READY / RIPDPI-ERROR paths remain unchanged for now; this task…

The RIPDPI implementation MUST satisfy this portfolio criterion: Existing RIPDPI-READY / RIPDPI-ERROR paths remain unchanged for now; this task only adds the pre-launch probe.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Existing RIPDPI-READY / RIPDPI-ERROR paths remain unchanged for now; this task only adds the pre-launch probe

### Requirement: REQ-SVC-1786264762917506-005 — Unit tests cover manager preflight behavior: (a) probe round-trip, (b) refusal…

The RIPDPI implementation MUST satisfy this portfolio criterion: Unit tests cover manager preflight behavior: (a) probe round-trip, (b) refusal on schema mismatch, (c) backward compatibility when the helper does not support --probe if the current release still allows schema 0.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Unit tests cover manager preflight behavior: (a) probe round-trip, (b) refusal on schema mismatch, (c) backward compatibility when the helper does not support --probe if the current release still allows schema 0

### Requirement: REQ-SVC-1786264762917506-006 — docs/native/relay-naiveproxy-runtime.md documents the probe line and the schema…

The RIPDPI implementation MUST satisfy this portfolio criterion: docs/native/relay-naiveproxy-runtime.md documents the probe line and the schema-version policy.

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that docs/native/relay-naiveproxy-runtime.md documents the probe line and the schema-version policy
