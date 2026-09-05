## Purpose

Keep bounded DNS capacity consistent with completed results.

## ADDED Requirements

### Requirement: REQ-AUDIT-DNS-CAPACITY — Release completed lookup capacity before publication

The DNS executor MUST release a completed lookup permit before publishing its result. The rule MUST apply to success, failure and caught panic. Running or hung lookups MUST retain their permits until completion.

#### Scenario: Immediate lookup after completion

- **WHEN** a caller receives a lookup result and submits another lookup
- **THEN** the completed lookup does not consume capacity for the next request

#### Scenario: Caught resolver panic

- **WHEN** the resolver panics and the executor returns unavailable
- **THEN** capacity is released before that unavailable result is observable
