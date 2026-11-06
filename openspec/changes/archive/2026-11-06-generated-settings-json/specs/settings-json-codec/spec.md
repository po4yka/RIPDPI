## Purpose

Keep settings backup values complete and typed, and reject damaged settings before a restore changes local data.

## ADDED Requirements

### Requirement: REQ-SETTINGS-JSON-001 — Generated field serialization

Settings JSON MUST use generated serializers for field names, types, defaults, and enum wire values. The current flat version 1 shape MUST remain the sole emitted shape.

#### Scenario: Valid settings round trip

- **WHEN** settings from each snapshot section are exported and restored
- **THEN** the supported values and existing domain normalization are preserved without section wrapper keys

#### Scenario: Missing and unknown fields

- **WHEN** a version 1 snapshot omits optional settings or contains unknown fields
- **THEN** declared defaults apply and unknown fields do not change known settings

### Requirement: REQ-SETTINGS-JSON-002 — Invalid values fail before mutation

Invalid known scalar, list, enum, and nested values MUST fail decoding rather than become defaults, strings, or missing elements. An invalid settings snapshot MUST abort backup restore before any stored data changes.

#### Scenario: Damaged settings in a valid archive

- **WHEN** a valid backup archive contains an invalid known settings value
- **THEN** restore reports failure and preserves settings, groups, and rules

#### Scenario: Unsupported format

- **WHEN** a snapshot declares an unsupported or invalid format version
- **THEN** decoding fails

### Requirement: REQ-SETTINGS-JSON-003 — Keep restore safety boundaries

PIN and LAN authentication tokens MUST be excluded from export and ignored on import. Existing AWG wire-profile validation and fake-SNI acknowledgement checks MUST remain active.

An omitted disabled AWG configuration MUST stay off after export and restore. AWG validation MUST also cover a custom preset inferred from the supplied settings, using the original profile and header values.

#### Scenario: Injected credentials

- **WHEN** imported JSON contains a PIN or LAN authentication token
- **THEN** neither value enters restored settings

#### Scenario: Unsafe transport settings

- **WHEN** settings lack a required AWG wire profile or fake-SNI acknowledgement
- **THEN** existing refusal or sanitization rules apply

#### Scenario: Empty disabled AWG settings

- **WHEN** a snapshot omits all AWG fields and is restored, exported, and restored again
- **THEN** AWG remains off and the second restore succeeds

#### Scenario: Incomplete inferred custom AWG settings

- **WHEN** disabled settings imply a custom preset but omit its required wire profile or headers
- **THEN** restore fails before changing stored data
