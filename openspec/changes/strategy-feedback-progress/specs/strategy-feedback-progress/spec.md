## ADDED Requirements

### Requirement: REQ-ENG-1795281317139456-001 — Defer TCP strategy success

The relay SHALL forward an accepted response at the existing forwarding point. It SHALL commit positive strategy feedback only after clean completion of the relay.

#### Scenario: A first response is followed by a freeze

- **WHEN** the client receives the first response and the relay later freezes
- **THEN** the relay does not store success for that attempt

### Requirement: REQ-ENG-1795281317139456-002 — Keep QUIC Retry provisional

The relay SHALL forward a correlated Retry immediately and preserve response correlation. Retry without a later correlated server Initial SHALL NOT commit strategy success.

#### Scenario: The server sends Retry alone

- **WHEN** a correlated Retry arrives and no correlated server Initial follows
- **THEN** the client receives Retry and the learner records no positive strategy feedback
