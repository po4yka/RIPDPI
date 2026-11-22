## ADDED Requirements

### Requirement: REQ-DGN-1795320365568134-001 — Admit a bounded manual HTTPS download

A user-initiated scan SHALL accept one HTTPS URL, a valid expected size up to 1 MiB, and a SHA-256. It SHALL measure one transfer on the selected path and SHALL bound the accounted body bytes to the expected size plus one.

#### Scenario: The user enters a valid control endpoint

- **WHEN** all three fields are valid and the user starts the scan
- **THEN** the scan requests the control once on the selected path

### Requirement: REQ-DGN-1795320365568134-002 — Verify the download and preserve progress

The scan SHALL distinguish a verified body, timeout, early end of stream, wrong size, and wrong hash. It SHALL retain received bytes and timing of body progress without retaining the body or hash in result details.

#### Scenario: TLS and HTTP headers succeed but the body stops

- **WHEN** the response does not progress before the transfer deadline
- **THEN** the scan reports a timeout and the measured body progress
