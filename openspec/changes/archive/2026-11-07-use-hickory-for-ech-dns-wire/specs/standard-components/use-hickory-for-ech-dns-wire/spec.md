## Purpose

ECH DNS queries and replies must follow the same DNS wire rules as the main resolver without a second handwritten parser. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-HICKORY-FOR-ECH-DNS-WIRE-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: example.com and example.com. produce equivalent valid DNS questions and retain the requested transaction ID.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** example.com and example.com. produce equivalent valid DNS questions and retain the requested transaction ID.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Wrong-query, malformed, truncated, and invalid-owner replies remain rejected by the shared validation contract.

### Requirement: REQ-HICKORY-FOR-ECH-DNS-WIRE-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep query ID, question/owner/type/CNAME checks, intended IPv4-only behavior, bounded parsing, and stable error categories. Existing reply validation must remain.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Valid compressed and CNAME replies keep the existing address result and endpoint configuration behavior.
- **AND** Keep query ID, question/owner/type/CNAME checks, intended IPv4-only behavior, bounded parsing, and stable error categories. Existing reply validation must remain.
