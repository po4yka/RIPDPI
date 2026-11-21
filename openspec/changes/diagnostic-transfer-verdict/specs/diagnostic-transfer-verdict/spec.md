## ADDED Requirements

### Requirement: REQ-DGN-1795277862388284-001 — Observe the stages of a bounded HTTPS control transfer

An in-path strategy scan SHALL use the current diagnostic transport for at most one bounded HTTPS control transfer. It SHALL preserve the last confirmed stage when communication stops before the body completes.

#### Scenario: Response headers arrive before the body stalls

- **WHEN** TLS and HTTP headers succeed and the body stalls before completion
- **THEN** the report records the last confirmed stage without confirming completion

### Requirement: REQ-DGN-1795277862388284-002 — Require body completion for the active working verdict

The verdict SHALL require a complete framed HTTPS control body with HTTP 200, no detected block page, and a current owned route. It SHALL accept a complete nonempty body regardless of its size within the configured bound.

#### Scenario: A short response completes

- **WHEN** the control returns a short complete body through the verified active route
- **THEN** the active path is working
