## Purpose

Preserve the distinction between an observed false value and missing network evidence.

## ADDED Requirements

### Requirement: REQ-METADATA-UNKNOWN — Preserve unavailable evidence

The implementation MUST keep missing capabilities, Private DNS and roaming observations unknown. Legacy snapshots MUST NOT gain observation certainty from default values.

#### Scenario: Legacy snapshot without availability

- **WHEN** a legacy snapshot has false validation and captive portal flags without availability metadata
- **THEN** the UI and summary show unknown instead of a confirmed negative

#### Scenario: Observed negative values

- **WHEN** capabilities were captured and validation is false
- **THEN** the UI can show the observed false value

#### Scenario: Missing roaming fact

- **WHEN** neither fingerprint nor telephony provides roaming state
- **THEN** native cellular metadata is omitted instead of stating not roaming
