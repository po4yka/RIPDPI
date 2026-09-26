## Purpose

Define when a diagnostic scan may publish terminal success and how it retains report evidence after finalization fails.

## ADDED Requirements

### Requirement: REQ-DGN-1790430631972892-001 — Publish in-path success after finalization

The implementation MUST publish an in-path scan as completed only after required report and post-scan writes succeed.

#### Scenario: In-path finalization succeeds

- **WHEN** a terminal native report is accepted and all finalization writes succeed
- **THEN** the persisted session is completed with the accepted report

#### Scenario: Post-scan persistence fails

- **WHEN** a required post-scan write fails after the native report is accepted
- **THEN** the persisted session is failed, retains the report, and is never published as completed

#### Scenario: Late report follows manual-conflict cancellation

- **WHEN** a terminal report arrives after the session has been failed for manual-conflict cancellation
- **THEN** the failed status and cancellation summary remain authoritative while the report is retained

### Requirement: REQ-DGN-1790430631972892-002 — Preserve raw-path settlement order

The implementation MUST publish a raw-path terminal session only after the durable runtime settlement receipt is stored.

#### Scenario: Raw-path finalization fails

- **WHEN** report finalization fails before the raw-path runtime settlement is stored
- **THEN** the scan does not publish a completed session before the durable settlement receipt
