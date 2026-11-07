# standard-components/use-tempfile-for-native-atomic-writes Specification

## Purpose
The three native state writers must share safe temporary-file lifecycle behavior without losing durability or file permissions. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-TEMPFILE-FOR-NATIVE-ATOMIC-WRITES-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: A successful replacement exposes a complete state file with the expected contents and permissions.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** A successful replacement exposes a complete state file with the expected contents and permissions.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** A failed write or rename keeps the old state and cleans temporary files during normal error unwinding.

### Requirement: REQ-TEMPFILE-FOR-NATIVE-ATOMIC-WRITES-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep size limits, restrictive permissions and permission preservation, old-file retention on failure, and sync_all ordering. Drop cleanup is not a SIGKILL guarantee.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Each writer preserves its file and directory synchronization contract; existing file formats load unchanged.
- **AND** Keep size limits, restrictive permissions and permission preservation, old-file retention on failure, and sync_all ordering. Drop cleanup is not a SIGKILL guarantee.
