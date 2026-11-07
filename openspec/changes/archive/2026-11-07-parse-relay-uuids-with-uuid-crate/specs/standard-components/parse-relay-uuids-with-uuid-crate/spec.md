## Purpose

Use the uuid crate for VLESS, xHTTP, and Cloudflare origin parsing. Keep their canonical and compact forms and preserve existing TUIC syntax. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-RELAY-UUIDS-WITH-UUID-CRATE-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: Canonical and compact forms of one UUID produce the same independently specified 16 bytes in each relay.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** Canonical and compact forms of one UUID produce the same independently specified 16 bytes in each relay.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** -550e8400e29b41d4a716446655440000 and other misplaced-dash forms fail validation.

### Requirement: REQ-RELAY-UUIDS-WITH-UUID-CRATE-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. The three migrated parsers accept canonical-dashed and compact input without trimming. They reject URN and braced forms and misplaced dashes. TUIC keeps Uuid::parse_str(config.uuid.trim()), including existing URN and braced forms. Preserve credential bytes and error redaction.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** All four relays follow the documented input forms without changing credential serialization or byte order.
- **AND** The three migrated parsers accept canonical-dashed and compact input without trimming. They reject URN and braced forms and misplaced dashes. TUIC keeps Uuid::parse_str(config.uuid.trim()), including existing URN and braced forms. Preserve credential bytes and error redaction.
