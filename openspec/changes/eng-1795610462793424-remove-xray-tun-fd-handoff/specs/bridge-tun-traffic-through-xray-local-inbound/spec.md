## ADDED Requirements

### Requirement: REQ-ENG-1795610462793424-001 — The Xray provider uses a single managed TUN handoff

The Xray provider runtime configuration MUST expose the local inbound port as its only handoff setting. It MUST keep DNS ownership in the managed tunnel.

#### Scenario: Resolve the Xray upstream

- **WHEN** the Xray provider starts with a valid local inbound port
- **THEN** the managed tunnel MUST use the Xray SOCKS inbound at that port on loopback
- **AND** the managed tunnel MUST own DNS resolution

#### Scenario: Reject an invalid inbound port

- **WHEN** the Xray provider starts with an invalid local inbound port
- **THEN** startup MUST fail before starting the provider or the tunnel
