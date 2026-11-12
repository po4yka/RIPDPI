# tcp-fake-execution-safety Specification

## Purpose
Preserve application bytes and socket state on failed TCP fake execution and expose the operation that the runtime completed.

## Requirements

### Requirement: REQ-DPI06-PAGE-RESTORE — Restore queued bytes

The implementation MUST restore original page contents before normal transport state on every exit and preserve known send progress.

#### Scenario: Restore queued bytes on the affected path

- **WHEN** an error follows a partial enqueue
- **THEN** retained pages contain original bytes and known progress is not reported as zero.

### Requirement: REQ-DPI06-FIRST-TTL — Preserve first-real TTL

The implementation MUST preserve TTL 1 when existing modifiers select raw transport while keeping the default nonroot path.

#### Scenario: Preserve first-real TTL on the affected path

- **WHEN** a fake modifier selects raw transport
- **THEN** the original first region retains TTL 1.

### Requirement: REQ-DPI06-TTL-ROLLBACK — Restore boundary state

The implementation MUST restore saved TTL or hop limit after write or wait failure and preserve the first error.

#### Scenario: Restore boundary state on the affected path

- **WHEN** boundary wait fails after TTL mutation
- **THEN** the saved socket TTL is restored.

### Requirement: REQ-DPI06-RECEIPT — Report actual execution

The implementation MUST report effective family and completed operation counts for boundary and empty-region paths.

#### Scenario: Report actual execution on the affected path

- **WHEN** a terminal offset runs only a write and wait
- **THEN** receipt records a lowered family and one completed wait.

### Requirement: REQ-DPI06-SECONDARY — Preserve selected payloads

The implementation MUST include selected secondary regions in supported custom orders and sequence modes or reject before sending.

#### Scenario: Preserve selected payloads on the affected path

- **WHEN** configuration accepts secondary payload and custom order
- **THEN** both payloads occur at defined sequence offsets.

### Requirement: REQ-DPI06-SEQ-WRAP — Accept TCP sequence wrap

The implementation MUST compute valid sequence additions modulo 2^32 and separately validate lengths.

#### Scenario: Accept TCP sequence wrap on the affected path

- **WHEN** sequence 4294967294 has offset 4
- **THEN** the result is 2.

### Requirement: REQ-DPI06-DOCS — State transport boundaries

The implementation MUST distinguish stream, optional raw, ordered raw, and MultiDisorder and not describe a probe as wire success.

#### Scenario: State transport boundaries on the affected path

- **WHEN** a reader selects a custom order
- **THEN** documentation states actual raw requirements and evidence limits.
