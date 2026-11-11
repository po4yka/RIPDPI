# finalmask-boundary Specification

## Purpose
Define safe and testable mask behavior across settings admission and native carrier execution.

## Requirements

### Requirement: REQ-FINALMASK-FRAGMENT — Bound fragment resources

The implementation MUST reject fragment counts outside 1..16 and bound intermediate allocation by payload length.

#### Scenario: Bound fragment resources

- **WHEN** a profile requests i32::MAX fragments
- **THEN** admission rejects it without allocation or a network dial

### Requirement: REQ-FINALMASK-PASSWORD — Keep exact Sudoku passwords

The implementation MUST use the exact bytes of every nonblank Sudoku password.

#### Scenario: Keep exact Sudoku passwords

- **WHEN** a password contains spaces or Unicode
- **THEN** the upstream and client tables agree for that exact password

### Requirement: REQ-FINALMASK-ERROR — Close failed carriers

The implementation MUST surface terminal I/O errors and release both bridge pumps while retaining normal half-close behavior.

#### Scenario: Close failed carriers

- **WHEN** one pump fails while the sibling is pending
- **THEN** the caller observes failure and carrier capacity is released after owner drop

### Requirement: REQ-FINALMASK-VALIDATION — Validate active parameters consistently

The implementation MUST validate all active mask parameters before runtime readiness and ignore inactive fields.

#### Scenario: Validate active parameters consistently

- **WHEN** fragment retains an invalid randRange from another mode
- **THEN** fragment remains usable and active invalid noise or header settings fail early

### Requirement: REQ-FINALMASK-UNKNOWN — Reject unknown mask types

The implementation MUST retain or reject unknown nonempty mask types so they cannot become off.

#### Scenario: Reject unknown mask types

- **WHEN** settings import contains sudkou
- **THEN** validation rejects the requested type instead of opening an unmasked carrier

### Requirement: REQ-FINALMASK-REFERENCE — Define the peer subset

The implementation MUST record an exact upstream reference and executable peer configurations with explicit limits.

#### Scenario: Define the peer subset

- **WHEN** a user selects a documented mask example
- **THEN** the pinned peer accepts the example and the documented layer order preserves application bytes
