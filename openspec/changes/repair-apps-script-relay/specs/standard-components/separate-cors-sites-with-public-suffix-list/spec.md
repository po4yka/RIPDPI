## MODIFIED Requirements

### Requirement: REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: An explicitly permitted origin receives only the CORS headers allowed by the documented policy.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** An explicitly permitted origin receives only the CORS headers allowed by the documented policy.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Independent github.io and fly.dev tenants do not receive credential permission solely because they share a public suffix.

#### Scenario: Preserve origin authority

- **WHEN** an Apps Script client sends an OPTIONS preflight
- **THEN** the relay returns 501 without access-control permission headers
- **AND** scheme, port or a shared registrable domain MUST NOT grant origin permission
- **AND** the relay MUST NOT send the subsequent request on behalf of a failed preflight

#### Scenario: Reject malformed origins

- **WHEN** Origin is malformed, contains whitespace or control characters, has user information, a path, query, or fragment, or contains multiple values
- **THEN** the response is 501 with no access-control headers
- **AND** a missing Origin or access-control-request-method retains the existing 501 response

### Requirement: REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Existing supported same-site cases are reviewed against the explicit policy; malformed or unsupported origins fail closed without network PSL lookup.
- **AND** PSL domain grouping must not itself grant credential access. Define scheme and port treatment, localhost and IP behavior, invalid Origin rejection, and list update policy before implementation.
