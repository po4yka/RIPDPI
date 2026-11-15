## Purpose

Preserve the measured protocol, sample quality and execution state of the existing Kotlin ICMP and TCP timing checks.

## ADDED Requirements

### Requirement: REQ-DIA17-F1 — Network permission

The manual Detection runner SHALL disable TCP timing when the network request switch is off.

#### Scenario: Network permission boundary

- **WHEN** the master network switch is off
- **THEN** no TCP timing work is scheduled.

### Requirement: REQ-DIA17-F2 — Incomplete measurements

The implementation SHALL preserve skipped, failed and insufficient measurements as explicit states in classifiers, UI and exports.

#### Scenario: Incomplete measurements boundary

- **WHEN** all ICMP or TCP attempts fail
- **THEN** the result is inconclusive, not a completed negative check.

### Requirement: REQ-DIA17-F3 — Timing evidence

The UI SHALL describe timing as a network observation and SHALL NOT describe it as an observed Android VPN capability.

#### Scenario: Timing evidence boundary

- **WHEN** only LOW timing evidence is present
- **THEN** the explanation names a timing anomaly and retains review semantics.

### Requirement: REQ-DIA17-F4 — RTT sample quality

RTT classification SHALL require usable RU and foreign cohorts and SHALL reduce confidence for loss or high jitter among available targets.

#### Scenario: RTT sample quality boundary

- **WHEN** one of ten targets replies with high jitter and no foreign control
- **THEN** the result is inconclusive and produces no medium-confidence path assertion.

### Requirement: REQ-DIA17-F5 — TCP latency boundary

The TCP timing runner SHALL resolve a bounded endpoint before timing a connect and SHALL keep DNS and socket close outside the connect interval.

#### Scenario: TCP latency boundary boundary

- **WHEN** name resolution is slow but the loopback connect is fast
- **THEN** only the connect contributes to TCP latency.

### Requirement: REQ-DIA17-F6 — Cancellation ownership

Cancellation SHALL stop owned ping processes and close active TCP sockets before their work completes.

#### Scenario: Cancellation ownership boundary

- **WHEN** the user stops a check during process wait or connect
- **THEN** the owned operation is cancelled and does not continue as an earlier run.

### Requirement: REQ-DIA17-F7 — Numeric ping profile

The ping runner SHALL use numeric reply output for iputils and SHALL keep toybox argument compatibility.

#### Scenario: Numeric ping profile boundary

- **WHEN** the binary is an admitted ping profile
- **THEN** no reverse lookup is required to print a reply and arguments are supported.

