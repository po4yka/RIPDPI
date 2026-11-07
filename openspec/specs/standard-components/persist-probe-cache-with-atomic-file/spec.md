# standard-components/persist-probe-cache-with-atomic-file Specification

## Purpose
An interrupted probe-cache write must not destroy the previous complete cache. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-PERSIST-PROBE-CACHE-WITH-ATOMIC-FILE-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: A completed cache write survives reopen with the same entries and timestamps.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** A completed cache write survives reopen with the same entries and timestamps.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** An interrupted or failed replacement leaves the previous valid cache readable through AtomicFile.

### Requirement: REQ-PERSIST-PROBE-CACHE-WITH-ATOMIC-FILE-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep the mutex, 24-hour TTL, 10-entry limit, JSON shape, and safe fallback for an already corrupt file. No database or schema migration.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Existing JSON caches load without migration; expiry, entry limit, and corrupt-file fallback remain unchanged.
- **AND** Keep the mutex, 24-hour TTL, 10-entry limit, JSON shape, and safe fallback for an already corrupt file. No database or schema migration.
