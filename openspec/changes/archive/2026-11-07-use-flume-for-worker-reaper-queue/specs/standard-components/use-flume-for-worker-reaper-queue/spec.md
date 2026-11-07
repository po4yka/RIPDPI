## Purpose

WorkerReaper must support multiple consumers and bounded enqueue waits without a handwritten spin loop. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## ADDED Requirements

### Requirement: REQ-FLUME-FOR-WORKER-REAPER-QUEUE-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: Independent completed workers are joined concurrently without head-of-line blocking.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** Independent completed workers are joined concurrently without head-of-line blocking.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** A full or disconnected queue returns the original JoinHandle within the existing bounded enqueue policy.

### Requirement: REQ-FLUME-FOR-WORKER-REAPER-QUEUE-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep queue capacity 64, the 5 ms enqueue budget, returned JoinHandle ownership on rejection, worker-start failure handling, and shutdown order.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Capacity, pending counts, failed worker creation, and shutdown retain ownership safety without a Mutex-wrapped receiver or yield retry loop.
- **AND** Keep queue capacity 64, the 5 ms enqueue budget, returned JoinHandle ownership on rejection, worker-start failure handling, and shutdown order.
