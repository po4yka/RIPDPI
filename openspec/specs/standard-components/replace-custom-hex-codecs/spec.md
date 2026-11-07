# standard-components/replace-custom-hex-codecs Specification

## Purpose
Hex representations of existing fingerprints, digests, receipts, and configured keys must stay byte-for-byte identical with fewer codec copies. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-CUSTOM-HEX-CODECS-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: Each known byte vector produces exactly the previous lowercase hex string including leading zero bytes and existing prefixes.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** Each known byte vector produces exactly the previous lowercase hex string including leading zero bytes and existing prefixes.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Malformed keys, wrong lengths, and explicitly all-zero shared-priors keys remain rejected with existing error categories.

### Requirement: REQ-CUSTOM-HEX-CODECS-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep lowercase, prefixes, lengths, case acceptance, malformed-input errors, unset-key semantics, and all-zero-key rejection. Do not change hash/MAC algorithms or stored identity values.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Previously stored fingerprints, digests, receipts, and keys remain identical; build.rs receives the required direct hex build dependency.
- **AND** Keep lowercase, prefixes, lengths, case acceptance, malformed-input errors, unset-key semantics, and all-zero-key rejection. Do not change hash/MAC algorithms or stored identity values.

#### Scenario: Retain manifest hash validation

- **WHEN** a signed manifest contains an uppercase valid hash
- **THEN** verification accepts the same bytes and retains the original hash text.
- **AND** wrong lengths, invalid digits, and surrounding whitespace produce BadHashFormat.
- **AND** a well-formed all-zero hash reaches payload comparison and produces HashMismatch for a nonzero payload hash; only a configured public key rejects all-zero bytes as a key error.
