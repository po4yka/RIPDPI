## Purpose

Define explicit, fail-closed AmneziaWG revision handling from imported bundle
through Android runtime activation and staged interoperability evidence.

## ADDED Requirements

### Requirement: REQ-TRN-1786299802611226-001 — Imported profiles retain explicit revision

The client MUST validate, persist, export, and restore the canonical AWG revision
and implementation provenance without inferring either from parameter shape.

#### Scenario: A current-revision bundle is imported

- **GIVEN** a canonical entry with a supported revision and matching provenance
- **WHEN** the profile is imported and restored
- **THEN** the same revision identity MUST reach activation unchanged

### Requirement: REQ-TRN-1786299802611226-002 — Unsupported combinations fail before runtime

Missing, unknown, substituted, or inconsistent revision/provenance/fingerprint
combinations MUST be rejected before native runtime activation.

#### Scenario: Entry declares an unsupported later revision

- **GIVEN** a syntactically valid but unsupported AWG revision
- **WHEN** activation is requested
- **THEN** the client MUST return a typed unsupported-revision result and MUST NOT start the runtime

### Requirement: REQ-TRN-1786299802611226-003 — Current behavior remains compatible

The explicit revision migration MUST preserve current profile parsing,
persistence, and wire behavior for the established supported revision.

#### Scenario: Existing current-revision profile is migrated

- **GIVEN** a valid stored profile created before the explicit revision field
- **WHEN** migration assigns the only historically supported revision
- **THEN** its normalized configuration and runtime behavior MUST remain equivalent

### Requirement: REQ-TRN-1786299802611226-004 — Later revision remains staging gated

A later supported implementation MUST remain ineligible for production until
upstream-pinned fixtures, cross-stack server interop, and physical arm64 evidence pass.

#### Scenario: Local fixtures pass but device evidence is absent

- **GIVEN** passing parser and native fixture tests for a later revision
- **WHEN** production eligibility is evaluated
- **THEN** the revision MUST remain staging-only with an explicit missing-device evidence state

### Requirement: REQ-TRN-1786299802611226-005 — Diagnostics explain revision failures safely

The client MUST distinguish unsupported revision, stale profile, implementation
mismatch, and ordinary transport failure without exposing configuration values.

#### Scenario: Revision fingerprint does not match

- **GIVEN** a profile whose revision-bound fingerprint is inconsistent
- **WHEN** validation or activation runs
- **THEN** diagnostics MUST report a typed stale-or-mismatched profile outcome with no parameter dump
