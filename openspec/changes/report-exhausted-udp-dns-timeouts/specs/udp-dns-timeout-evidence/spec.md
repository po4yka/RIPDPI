## Purpose

Keep UDP DNS diagnosis aligned with successful recovery evidence and avoid unsupported cause attribution.

## ADDED Requirements

### Requirement: REQ-UDP-TIMEOUT-001 — Distinguish failed retries from recovery

The classifier MUST report `dns_unavailable` when UDP times out without recovery even when encrypted DNS works. It MUST retain `udp_plain_dns_unstable` for recovered matching answers.

#### Scenario: Exhausted retries

- **WHEN** all UDP attempts time out and encrypted DNS succeeds
- **THEN** the outcome is `dns_unavailable`, not a transient recovery or blocking claim.

#### Scenario: Observed recovery

- **WHEN** UDP recovers on retry and its answers match encrypted DNS
- **THEN** the outcome remains `udp_plain_dns_unstable`.

#### Scenario: Existing evidence and contracts

- **WHEN** the result is reported
- **THEN** UDP errors and attempts plus encrypted answers remain available in existing details, without new logs, wire fields, or requests.
