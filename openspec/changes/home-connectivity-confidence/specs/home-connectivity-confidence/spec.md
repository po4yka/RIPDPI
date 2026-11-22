## ADDED Requirements

### Requirement: REQ-DGN-1795348431215897-001 — Qualify connectivity confidence with comparable measurements

Home SHALL show the targets and stages measured on both paths and the count of complete measurements. High confidence SHALL require complete, consistent, repeated direct evidence and relevant active-path evidence for multiple targets.

#### Scenario: One direct failure with no active-path result

- **WHEN** one control passes and one target fails on the direct path without an active-path result
- **THEN** Home reports the observed pattern with low confidence and shows the measured direct stages with an unmeasured active path

### Requirement: REQ-DGN-1795348431215897-002 — Report observed HTTP recovery

Home SHALL show HTTP recovery only when the same configured target fails the direct HTTP check and completes the active-path HTTP check. Partial, unmeasured, and conflicting stages SHALL remain distinguishable. The redacted archive SHALL retain their statuses and counts.

#### Scenario: A complete HTTP pair recovers

- **WHEN** the direct HTTP check fails and the matching active-path HTTP check succeeds
- **THEN** Home shows the recovery separately from the direct diagnosis

#### Scenario: The pair is incomplete or conflicting

- **WHEN** a pair lacks a complete report or has inconsistent measurements
- **THEN** Home does not use that pair to establish HTTP recovery
