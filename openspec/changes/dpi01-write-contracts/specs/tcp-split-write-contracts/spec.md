## Purpose

Keep split configuration and write failures explicit without changing accepted bytes or adding packet techniques.

## ADDED Requirements

### Requirement: REQ-SPLIT-ADMISSION — Reject unsupported offsets

The implementation MUST reject Split repetition greater than one or nonzero skip and extra colon fields. Supported TLS-record repetition MUST remain valid.

#### Scenario: Unsupported configuration

- **WHEN** a Split has repeated offsets, nonzero skip, or extra expression fields
- **THEN** admission rejects it before a socket write

### Requirement: REQ-SPLIT-ARITHMETIC — Check arithmetic

The implementation MUST return a controlled error on offset overflow in any build profile.

#### Scenario: Extreme delta

- **WHEN** marker position plus delta exceeds the integer range
- **THEN** planning fails without panic or wrap

### Requirement: REQ-SPLIT-PROGRESS — Preserve progress

The implementation MUST retain known progress in plain and strategy errors and prevent first-write replay after nonzero progress.

#### Scenario: Partial fallback write

- **WHEN** a socket accepts a prefix and a later write fails
- **THEN** the error retains that count and classification refuses replay

### Requirement: REQ-SPLIT-CANCEL — Prevent cancelled replay

The implementation MUST prevent replay after cancelled in-flight writes with unknown progress without inventing a byte count.

#### Scenario: Shutdown during write

- **WHEN** shutdown ends a write whose syscall returns no progress
- **THEN** error handling refuses replay and peer bytes remain an input prefix

### Requirement: REQ-SPLIT-DELAY — Delay the tail

The implementation MUST preserve configured delay before a nonempty tail in either executor.

#### Scenario: Last split has a tail

- **WHEN** the last executed split has delay and remaining bytes
- **THEN** the executor delays the tail without losing or repeating bytes

### Requirement: REQ-SPLIT-SCOPE — Preserve boundaries

The implementation MUST retain activation and access boundaries without new external probes, payload logs, or packet-boundary guarantees.

#### Scenario: Inactive split

- **WHEN** activation excludes Split
- **THEN** plain forwarding retains the failure-progress contract
