## ADDED Requirements

### Requirement: REQ-VPN-1795271597027505-001 — Schedule a bounded recovery pass

The payload selector SHALL coalesce bursts of network and failure signals, wait for the stabilization window, and admit at most one recovery attempt per 30 seconds. It SHALL apply current network and route checks and preserve the configured outage backoff.

#### Scenario: A burst of callbacks arrives during handover

- **WHEN** repeated network and service callbacks arrive before the stabilization window ends
- **THEN** the selector checks the current route at most once after the window and observes its normal service ownership and failure thresholds

### Requirement: REQ-VPN-1795271597027505-002 — Share a durable probe budget

The selector SHALL charge regular and recovery payload passes to the same durable budget, with recovery headroom when there is capacity for two passes. An exhausted budget SHALL defer the pass without treating the deferral as a transport failure.

#### Scenario: A regular pass reaches the recovery headroom

- **WHEN** a regular probe would spend the bytes held for recovery
- **THEN** it is deferred and a recovery probe can spend the remaining bytes within the total limit
