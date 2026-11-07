# standard-components/replace-bounded-heap-with-binary-heap Specification

## Purpose
UDP association eviction must use the Rust standard heap without changing bounded history or stale-entry handling. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-BOUNDED-HEAP-WITH-BINARY-HEAP-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: When an association must be evicted, the same oldest eligible association is selected.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** When an association must be evicted, the same oldest eligible association is selected.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Stale entries and reused source addresses cannot evict a newer association generation.

### Requirement: REQ-BOUNDED-HEAP-WITH-BINARY-HEAP-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep the explicit 2048-entry threshold, heap rebuilding, association ID/generation checks, eviction order, and bounded activity history. Capacity is not a size limit.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** History stays bounded after more than 2048 activity records; no caller, manifest, API snapshot, or architecture entry still requires ripdpi-collections.
- **AND** Keep the explicit 2048-entry threshold, heap rebuilding, association ID/generation checks, eviction order, and bounded activity history. Capacity is not a size limit.
