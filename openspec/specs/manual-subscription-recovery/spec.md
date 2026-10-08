# manual-subscription-recovery Specification

## Purpose
Allow an explicit user action to recover a renewed subscription without replaying bootstrap delivery or causing automatic retry loops.

## Requirements

### Requirement: REQ-SUB-MANUAL-RECOVER - Recheck cached terminal state

The status screen MUST offer manual refresh for expired and invalidated long-lived subscriptions. Manual refresh MUST fetch a long-lived subscription despite a cached terminal failure or expired token. A valid non-expired response MUST replace the old expiry and clear the failure.

#### Scenario: Same URL renewed

- **WHEN** the user refreshes a subscription with expired metadata or a terminal failure and the server returns valid current profiles
- **THEN** the existing group becomes active with current server metadata and members

### Requirement: REQ-SUB-RECOVERY-SAFETY - Preserve retry and expiry safeguards

Automatic refresh MUST continue to skip cached terminal state and expiry. Every refresh MUST reject bootstrap replay and expired response content. An empty 304 response MUST NOT clear a cached failure. A failed nonterminal manual recheck MUST preserve a prior terminal block on automatic retries.

#### Scenario: Background or bootstrap attempt

- **WHEN** background refresh sees terminal or expired metadata, or manual refresh targets bootstrap
- **THEN** no HTTP request is sent

#### Scenario: Server still rejects recovery

- **WHEN** a manual recheck returns a terminal HTTP failure, an expired payload, or an empty 304 response
- **THEN** the attempt fails and existing members are preserved
