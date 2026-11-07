# standard-components/use-official-okhttp-coroutine-adapter Specification

## Purpose
HTTP probes must cancel calls and release responses when cancellation wins after the callback but before coroutine delivery. This capability defines the behavior, failure boundary, and compatibility checks required for the audited replacement.

## Requirements

### Requirement: REQ-OFFICIAL-OKHTTP-COROUTINE-ADAPTER-BEHAVIOR — Correct standard-component behavior

The implementation MUST satisfy the following observable behavior: A successful probe consumes and closes its response while keeping its existing payload verdict.

#### Scenario: Process supported input

- **WHEN** the owning operation receives the supported input described above
- **THEN** A successful probe consumes and closes its response while keeping its existing payload verdict.

#### Scenario: Reject invalid or failed input

- **WHEN** the operation reaches the malformed, cancelled, full, or failed case relevant to this path
- **THEN** Cancellation after the callback but before continuation delivery closes the undelivered response and cancels the call.

### Requirement: REQ-OFFICIAL-OKHTTP-COROUTINE-ADAPTER-COMPAT — Preserve bounded compatibility and privacy

The implementation MUST preserve its existing unaffected contracts. Keep call deadlines, EventListener, status and payload checks, socket routing, and cancellation propagation. Do not replace specialized probe payloads.

#### Scenario: Retain existing behavior

- **WHEN** existing supported data and callers use the replacement
- **THEN** Timeouts, failed HTTP status, invalid payloads, EventListener events, and local SOCKS routing retain their contracts.
- **AND** Keep call deadlines, EventListener, status and payload checks, socket routing, and cancellation propagation. Do not replace specialized probe payloads.
