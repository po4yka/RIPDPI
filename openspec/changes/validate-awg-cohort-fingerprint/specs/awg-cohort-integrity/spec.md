## Purpose

Prevent AWG imports from accepting cohort metadata that does not describe the supplied obfuscation parameters.

## ADDED Requirements

### Requirement: REQ-AWG-COHORT-REJECT — Reject inconsistent cohort metadata

The importer MUST reject an AWG entry when a present cohort fingerprint is malformed or differs from the SHA-256 fingerprint of its parsed parameters. It MUST preserve valid sibling profiles and report a fixed reason without metadata values.

#### Scenario: Invalid metadata in a mixed bundle

- **WHEN** an AWG entry has a mismatched, empty, null, non-string, or malformed cohort fingerprint
- **THEN** that entry is excluded, a typed rejection is returned, and valid sibling profiles remain available

### Requirement: REQ-AWG-COHORT-COMPAT — Preserve valid and legacy imports

The importer MUST accept an exact matching fingerprint and MUST keep accepting AWG entries and INI files without fingerprint metadata. The hash algorithm MUST remain compatible with the paired server golden.

#### Scenario: Valid or absent metadata

- **WHEN** a bundle has an exact matching fingerprint or has no fingerprint field
- **THEN** the AWG profile remains importable with its original parameters
