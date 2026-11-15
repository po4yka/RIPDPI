## ADDED Requirements

### Requirement: REQ-DIA10-TRANSFER — Report the observed transfer and coverage

The UI SHALL identify the bundled throughput endpoints as neutral controls. It SHALL show that no provider comparison is available when the run has no eligible test endpoint. The probe SHALL use the same eligibility rule for its measurement outcome and aggregate samples. Reports SHALL distinguish partial sample coverage and body transfer timing from full request timing.

#### Scenario: A neutral throughput profile completes

- **WHEN** the bundled profile records only neutral downloads
- **THEN** the user sees the neutral measurements and the comparison remains unavailable

#### Scenario: A valid response fills the byte window

- **WHEN** a supported successful HTTP response fills the configured window
- **THEN** the result and sample aggregate both include the measured transfer

### Requirement: REQ-DIA10-PT — Report and bound component checks

The PT tool SHALL show results for the configured TCP, HTTP HEAD, broker HTTP, and STUN Binding checks. It SHALL identify an endpoint response separately from a timeout or local error. It SHALL verify the STUN endpoint, transaction and required response attributes. The tool SHALL bound its target count and I/O and close active calls when it cancels a run.

#### Scenario: An HTTP endpoint answers or redirects

- **WHEN** a front or broker returns an HTTP status
- **THEN** the tool shows the status as evidence for that endpoint and expresses no transport verdict

#### Scenario: A valid response follows an unrelated STUN packet

- **WHEN** a valid Binding Success arrives before the deadline after an unrelated packet
- **THEN** only the valid response determines the Binding result

#### Scenario: Privacy mode changes during a check

- **WHEN** the user enables privacy mode during the check
- **THEN** the active network work stops and the tool publishes no stale result

### Requirement: REQ-DIA10-ENDPOINT — Keep endpoint failures and retry evidence distinct

Service and circumvention probes SHALL distinguish skipped, responding, failed, and incomplete operations. They SHALL preserve the status and detail of each bootstrap attempt and the observed QUIC reply type. Path comparison SHALL preserve the measured endpoint outcomes. NAT64 attempts SHALL use separate shares of the address-family budget.

#### Scenario: The bootstrap retry succeeds

- **WHEN** the first HTTP response fails the check and a retry succeeds
- **THEN** the final status and detail describe the retry and the report retains the first attempt

#### Scenario: An endpoint refuses one request and another request times out

- **WHEN** two path checks for the same endpoint record HTTP 503 and a timeout
- **THEN** the comparison records neither path as successful for that operation

#### Scenario: The first NAT64 path does not answer

- **WHEN** the DNS result has two eligible prefixes and the first reaches its attempt deadline
- **THEN** the second prefix gets a TCP attempt within the remaining budget

### Requirement: REQ-DIA10-MATRIX — Preserve unstable target evidence

The selective matrix SHALL label unstable targets separately from targets that reproduce a result on every attempt. It SHALL show actual and planned coverage.

#### Scenario: A target alternates between success and failure

- **WHEN** a repeated matrix records a success and a failure for one target
- **THEN** the report identifies the unstable target and shows the recorded and planned attempts
