## Purpose

Preserve precise DNS response facts so users can distinguish negative answers, transport failures and uncertain resolver differences.

## ADDED Requirements

### Requirement: REQ-DNS-COLLECT — Preserve query-bound DNS responses
The diagnostic system SHALL preserve per-source response semantics for the existing address query, including RCODE, query type, answer versus NODATA versus NXDOMAIN, SERVFAIL, REFUSED, other RCODE, truncation, timeout, malformed and transport errors. It SHALL bind received evidence to the query and bound metadata. It SHALL keep CNAME targets, address TTL bounds, SOA negative TTL when available and numeric EDE codes, without EDE free text or raw packet storage.

#### Scenario: A valid negative response arrives
- **WHEN** a matching DNS response contains NXDOMAIN or NOERROR with no requested address
- **THEN** the report preserves negative semantics independently from transport failures

#### Scenario: A mismatched or incomplete response arrives
- **WHEN** response identity, question or owner validation fails, or TC is set
- **THEN** it does not become a usable address answer or a complete NODATA assertion

### Requirement: REQ-DNS-EXPLAIN — Explain evidence without provider attribution
The system SHALL show localized per-source DNS facts in current and historical sessions, keep legacy missing metadata explicit and distinguish resolver disagreement from proof of interference. Existing list-based tools SHALL not label empty answers as confirmed blocking or IP disagreement as confirmed substitution.

#### Scenario: Resolvers return different addresses
- **WHEN** valid answers differ between resolvers
- **THEN** the UI describes disagreement and its uncertainty without declaring a provider block

#### Scenario: A legacy report is opened
- **WHEN** a report lacks new metadata
- **THEN** the UI shows that metadata was not collected and does not invent NOERROR or NODATA

### Requirement: REQ-DNS-EXPORT — Keep compatible private exports
The system SHALL preserve new evidence through existing storage and redacted JSON/text exports, omit absent optional metadata and redact CNAME hostnames. It SHALL retain strict runtime resolver behavior.

#### Scenario: Export contains aliases and extended errors
- **WHEN** a diagnostic report is exported
- **THEN** aliases are redacted, EDE contains numeric codes only and old reports retain their existing shape
