## Purpose

SOCKS authentication must use the existing maintained constant-time comparison primitive in both runtime paths. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-LIBRARY-SOCKS-SECRET-COMPARISON-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: Correct username and password authenticate through the credentialed listener.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** Correct username and password authenticate through the credentialed listener.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** An incorrect username or password is rejected without logging either credential.

### Requirement: REQ-LIBRARY-SOCKS-SECRET-COMPARISON-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Evaluate username and password comparisons without short-circuit; preserve unequal-length rejection and redacted authentication errors. Do not claim to hide credential lengths.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Both authentication paths use the same library primitive; external SOCKS negotiation and unequal-length results remain unchanged.
- **AND** Evaluate username and password comparisons without short-circuit; preserve unequal-length rejection and redacted authentication errors. Do not claim to hide credential lengths.
