## ADDED Requirements

### Requirement: REQ-DGN-1795363995827246-001 — Exercise the verified transfer pair on Android

The test SHALL run the manual RAW profile with real HTTPS transfers and read the persisted diagnostic results. Its fixture certificate SHALL be available only to explicitly configured instrumentation builds.

#### Scenario: The long response stops during the body

- **WHEN** the short response completes and the long response stops after the fixture prefix
- **THEN** Android history shows a verified short result and a timed-out long result with the correct byte counts and both body timing measurements
