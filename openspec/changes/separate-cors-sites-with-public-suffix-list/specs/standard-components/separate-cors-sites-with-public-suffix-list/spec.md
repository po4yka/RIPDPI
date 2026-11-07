## Purpose

Credential policy must distinguish independent tenants below public and private suffixes. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: An explicitly permitted origin receives only the CORS headers allowed by the documented policy.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** An explicitly permitted origin receives only the CORS headers allowed by the documented policy.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Independent github.io and fly.io tenants do not receive credential permission solely because they share a public suffix.

### Requirement: REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Existing supported same-site cases are reviewed against the explicit policy; malformed or unsupported origins fail closed without network PSL lookup.
- **AND** PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.
