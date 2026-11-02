# app-lock-reset-notice Specification

## Purpose
Define how the app lock reset notice is persisted and shown. Portfolio task: `DAT-1793534707148297`.

## Requirements

### Requirement: REQ-DAT-1793534707148297-001 — The reset is persisted

When the PIN key is lost and the app wipes the PIN, the app MUST persist a flag in the same settings update that records the reset.

#### Scenario: Key lost

- **WHEN** the PIN key is unavailable while a PIN is stored
- **THEN** the PIN is wiped, app lock is off, and the reset flag is set in one update

### Requirement: REQ-DAT-1793534707148297-002 — The notice survives process death

The Settings list MUST show the app lock reset notice while the flag is set, including after the process restarts.

#### Scenario: Restart after reset

- **WHEN** the process restarts while the flag is set
- **THEN** the Settings list shows the notice

### Requirement: REQ-DAT-1793534707148297-003 — The flag clears on acknowledgement or a new PIN

The flag MUST clear when the user acknowledges the notice or sets a new PIN.

#### Scenario: Acknowledge

- **WHEN** the user acknowledges the notice
- **THEN** the flag is cleared and the notice no longer shows

#### Scenario: New PIN

- **WHEN** the user sets a new PIN
- **THEN** the flag is cleared and the notice no longer shows

### Requirement: REQ-DAT-1793534707148297-004 — Old settings decode with the flag off

The new field MUST use the next free proto field number and an inert default. Bytes written before the field existed MUST decode with the flag off.

#### Scenario: Old bytes

- **WHEN** settings bytes written without the field are read
- **THEN** the flag is off and every other field is unchanged

### Requirement: REQ-DAT-1793534707148297-005 — Backup handling is decided and documented

The backup and restore handling of the flag MUST be recorded, and `CONFIG_CONTRACTS.md` MUST document the field.

#### Scenario: Backup round trip

- **WHEN** settings are backed up and restored
- **THEN** the flag follows the documented decision
