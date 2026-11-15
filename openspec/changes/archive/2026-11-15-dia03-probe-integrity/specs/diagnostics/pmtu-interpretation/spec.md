## ADDED Requirements

### Requirement: REQ-DIA03-TIMEOUT — Preserve PMTU budget source

PMTU evidence MUST distinguish its local timeout from an earlier scan deadline and from cancellation. These states MUST NOT become a completed measurement.

#### Scenario: Local timeout

- **WHEN** the probe's own timer expires without a scan deadline
- **THEN** its reason is timeout and its observation window is incomplete

#### Scenario: Scan deadline

- **WHEN** the scan deadline expires before the probe's own timer
- **THEN** its reason is deadline_exceeded

#### Scenario: Cancellation

- **WHEN** the owner cancels a running probe
- **THEN** the reason is cancelled and acknowledged partial facts remain available
