## ADDED Requirements

### Requirement: REQ-DGN-1795331701582623-001 — Compare three TLS modes on the same HTTPS endpoint

A manual scan SHALL run TLS 1.3, ECH GREASE, and real ECH with the same pinned address, HTTPS origin, port, and selected route. The real ECH leg SHALL use published DNS HTTPS configuration and SHALL require ECH acceptance.

#### Scenario: The origin has no ECH configuration

- **WHEN** the DNS HTTPS lookup succeeds without an ECH configuration
- **THEN** the real ECH row indicates that the configuration is absent

### Requirement: REQ-DGN-1795331701582623-002 — Verify and report each transfer

The scan SHALL check each response within the time and size limits. It SHALL report the setup, handshake, HTTP, and body outcomes separately. The result SHALL contain the observed byte count for each leg.

#### Scenario: TLS succeeds and the body stops

- **WHEN** a leg receives response headers and the body stops progressing
- **THEN** its row reports the body timeout and counts the bytes received before it stopped
