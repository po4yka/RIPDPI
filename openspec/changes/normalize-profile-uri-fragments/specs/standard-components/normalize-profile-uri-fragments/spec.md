## Purpose

Imported profile names must preserve plus signs, percent characters, and Unicode without a second decode. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-NORMALIZE-PROFILE-URI-FRAGMENTS-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: A profile with fragment #A%2BB imports with display name A+B.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** A profile with fragment #A%2BB imports with display name A+B.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Malformed percent encoding returns the existing typed failure without partial import or credential disclosure.

### Requirement: REQ-NORMALIZE-PROFILE-URI-FRAGMENTS-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep each supported share-link format and export/import round trips. Do not change relay activation or stored profile identifiers.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Fragments with literal plus, %25, spaces, and Unicode retain one decode across each supported format and export/import.
- **AND** Keep each supported share-link format and export/import round trips. Do not change relay activation or stored profile identifiers.
