## Purpose

VLESS, xHTTP, Cloudflare origin, and TUIC must validate the same supported UUID forms. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

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

The implementation MUST preserve its existing unaffected contracts. Keep supported canonical and compact forms and byte order. Do not silently add URN or braced forms; reject misplaced dashes with redacted errors.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** All four relays follow the documented input forms without changing credential serialization or byte order.
- **AND** Keep supported canonical and compact forms and byte order. Do not silently add URN or braced forms; reject misplaced dashes with redacted errors.
