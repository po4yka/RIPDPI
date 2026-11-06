## Purpose

Use a common DNS library to keep Kotlin resolution and probe evidence consistent while retaining explicit network routing and privacy rules.

## ADDED Requirements

### Requirement: REQ-KDNS-WIRE — Validate library-decoded messages

Kotlin DNS consumers MUST use a shared library codec and validate response ID, QR, opcode and the full expected question. Complete responses MUST reject malformed records and trailing bytes. Diagnostics MUST retain negative outcomes, aliases, TTL, SOA and numeric EDE metadata without exporting EDE free text.

#### Scenario: Unrelated or malformed reply

- **WHEN** a response has a different name, type, class, ID, invalid record or invalid compression
- **THEN** it cannot supply addresses or successful diagnostic evidence.

### Requirement: REQ-KDNS-TCP — Retry truncated direct resolution

The direct resolver MUST retry a matching truncated UDP response over TCP to the same server and port with the same network binding and bounded deadlines. TCP replies MUST pass the same validation and cannot remain truncated.

#### Scenario: Truncated UDP answer

- **WHEN** a matching UDP response has TC set
- **THEN** TCP supplies the complete validated answer or resolution fails without accepting partial addresses.

### Requirement: REQ-KDNS-PROBES — Preserve probe-specific evidence

DNS probes MUST use library query encoding, preserve requested EDNS payload sizes, and validate the question. UDP health probes MUST NOT use TCP fallback as UDP success. HTTPS records MUST match the requested host and retain valid ECH evidence and cancellation behavior.

#### Scenario: Sized UDP probe

- **WHEN** a UDP health probe requests a padded payload
- **THEN** the encoded query has the requested supported size and only its matching UDP response acknowledges it.

#### Scenario: HTTPS record response

- **WHEN** a DoH or system query receives unrelated, truncated or malformed HTTPS records
- **THEN** it cannot publish ECH readiness from those records.
