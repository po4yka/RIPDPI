## ADDED Requirements

### Requirement: REQ-DGN-1795361889557414-001 — One bounded RAW comparison

The manual profile SHALL download a short and a long object from one HTTPS origin and fixed IP over TCP. The two attempts SHALL share a deadline of at most 30 seconds. Each successful attempt SHALL verify its own size and SHA-256.

#### Scenario: Verified pair

- **WHEN** both objects reach the expected size, hash, and end of response
- **THEN** the report records two verified downloads

### Requirement: REQ-DGN-1795361889557414-002 — Preserve progress when a transfer stalls

Each result SHALL show received bytes and the times of first and last body progress when observed.

#### Scenario: Long response stalls

- **WHEN** the short response verifies and the long response stops making progress
- **THEN** the report records a timeout with the measured bytes and timing of the long response
