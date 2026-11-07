# standard-components/parse-native-urls-with-url-crate Specification

## Purpose
Native redirect and encrypted-DNS endpoint handling must extract valid hosts and resolve relative references against the current URL. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-NATIVE-URLS-WITH-URL-CRATE-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: https://host.example?token=x yields host host.example and request target /?token=x.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** https://host.example?token=x yields host host.example and request target /?token=x.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Invalid or disallowed redirect schemes and malformed endpoints fail within the existing redirect budget without leaking query credentials.

### Requirement: REQ-NATIVE-URLS-WITH-URL-CRATE-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep Apps Script front host and SNI transport, scheme restrictions, redirect budget, endpoint ports, and error redaction. Do not turn on an ordinary automatic HTTP redirect client.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** After an absolute redirect, a relative reference resolves against the new URL while the intended front/SNI transport remains in use.
- **AND** Keep Apps Script front host and SNI transport, scheme restrictions, redirect budget, endpoint ports, and error redaction. Do not turn on an ordinary automatic HTTP redirect client.
