## Purpose

Certificate import must accept valid DER and PEM bytes without first requiring UTF-8 text. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-CLIENT-CERTIFICATES-WITH-CERTIFICATE-FACTORY-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: The same certificate imported from binary DER and PEM produces equivalent normalized certificate bytes.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** The same certificate imported from binary DER and PEM produces equivalent normalized certificate bytes.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Oversized, empty, and malformed documents fail explicitly before storage; errors contain no key or certificate payload.

### Requirement: REQ-CLIENT-CERTIFICATES-WITH-CERTIFICATE-FACTORY-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep the 256 KiB input limit, explicit empty or invalid certificate rejection, certificate order, and existing private-key and PKCS12 import contracts.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** An ordered PEM chain retains its certificate order; private-key UTF-8 checks and PKCS12 behavior stay intact.
- **AND** Keep the 256 KiB input limit, explicit empty or invalid certificate rejection, certificate order, and existing private-key and PKCS12 import contracts.
