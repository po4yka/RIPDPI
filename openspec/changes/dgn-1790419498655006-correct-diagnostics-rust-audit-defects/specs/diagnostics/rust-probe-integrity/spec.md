## Purpose

Keep native diagnostic verdicts tied to complete probe evidence, available runtime capabilities, and the active scan lifetime.

## ADDED Requirements

### Requirement: REQ-DGN-1790419498655006-001 — Complete HTTP evidence

The implementation MUST reject an HTTP response whose headers are incomplete or whose body ends before a declared Content-Length.

#### Scenario: Server closes early

- **WHEN** a server closes before the header terminator or before the declared body length
- **THEN** the probe MUST report an error instead of an HTTP success

### Requirement: REQ-DGN-1790419498655006-002 — Telegram transfer response

The implementation MUST count a Telegram download or upload as successful only after a successful HTTP response status is observed.

#### Scenario: Server rejects a transfer

- **WHEN** the server returns a non-2xx response after a download request or upload body
- **THEN** the transfer MUST NOT have an `ok` status

### Requirement: REQ-DGN-1790419498655006-003 — Accurate baseline classification

The implementation MUST classify only failed strategy observations as baseline failures and MUST list every capability used by the current candidate.

#### Scenario: Successful HTTPS and mixed capabilities

- **WHEN** an HTTPS baseline succeeds and the active candidate needs more than one runtime capability
- **THEN** the baseline MUST have no failure class and the candidate MUST retain every requirement

### Requirement: REQ-DGN-1790419498655006-004 — Preserve terminal report

The implementation MUST preserve a completed scan report until it is consumed, including when the caller requests another scan on the same session.

#### Scenario: Restart before report retrieval

- **WHEN** a caller starts another scan before retrieving the previous terminal report
- **THEN** the new start MUST fail without deleting the previous report

### Requirement: REQ-DGN-1790419498655006-005 — Bound resolver workers

The implementation MUST retain a system DNS concurrency permit until the associated blocking resolver worker exits, including after caller cancellation or timeout.

#### Scenario: Resolver remains blocked after timeout

- **WHEN** a DNS lookup exceeds the caller deadline but its system worker remains blocked
- **THEN** it MUST continue to consume one concurrency permit until that worker exits

### Requirement: REQ-DGN-1790419498655006-006 — Honor scan deadline

The implementation MUST stop address attempts, delayed connection races, and temporary proxy readiness waits when the active scan deadline expires.

#### Scenario: Multiple unresponsive addresses

- **WHEN** a scan deadline expires during a multi-address connection or proxy startup
- **THEN** no later attempt or wait MUST extend work beyond that deadline

### Requirement: REQ-DGN-1790419498655006-007 — Dormant probe bounds

The implementation MUST parse valid DoH JSON independent of insignificant whitespace and MUST respect configured throughput byte caps.

#### Scenario: Whitespace and small byte cap

- **WHEN** a DoH JSON response includes spaces or a throughput cap is smaller than the read buffer
- **THEN** the parser MUST preserve the response meaning and the reader MUST NOT exceed the cap
