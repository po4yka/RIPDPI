## ADDED Requirements

### Requirement: REQ-DIA09-INPUT Valid target and work limits

The catalog MUST pair HTTPS targets with a valid TLS name and HTTP authority. Admission MUST reject invalid addresses and bound the actual request and candidate budgets with the defaults the runner uses.

#### Scenario: Select a catalog TCP profile
- **WHEN** a user selects a TCP profile
- **THEN** it schedules explicit plain targets and HTTPS targets with a usable TLS identity
- **AND** it does not send plain HTTP to an unqualified HTTPS endpoint

### Requirement: REQ-DIA09-HTTP Parse HTTP at the HEAD boundary

The probe MUST emit a valid HTTP/1.1 Host. It MUST recognize final HEAD responses and their metadata without consuming a body. It MUST reject malformed statuses and ambiguous framing.

#### Scenario: Receive a valid metadata-only response
- **GIVEN** an HTTP/1.1 HEAD request
- **WHEN** a final response contains legal transfer coding metadata and no body
- **THEN** the probe records the final response and can reuse the connection if the peer allows it

### Requirement: REQ-DIA09-MEASURE Preserve transfer evidence

The probe MUST count only application bytes accepted by write operations. It MUST distinguish completed writes, completed HEAD responses, coverage, and the observed failure stage. A single FIN, reset, truncated response, local error, or timeout MUST NOT establish a byte threshold or a network cause.

#### Scenario: A socket write fails before any bytes are accepted
- **WHEN** the write call fails before it accepts data
- **THEN** the local byte count stays zero
- **AND** the result identifies a write failure and reports no threshold conclusion

### Requirement: REQ-DIA09-ATTEMPTS Bound retries and stop canceled probes

The probe MUST retry at most one eligible TCP connect failure independent of TLS, honor cancellation during the active attempt, and preserve completed evidence if it stops. An SNI comparison MUST keep the HTTP authority fixed and retain evidence for each attempt. A candidate MUST complete sufficient coverage before it can count as a recovery.

#### Scenario: The deadline expires after a final HEAD response
- **WHEN** the scan budget ends while the next response is pending
- **THEN** the probe preserves the completed response and records that the deadline ended the attempt
- **AND** it starts no further connection

### Requirement: REQ-DIA09-REPORT Display consistent evidence

The report MUST agree with the typed TCP observation for success. The summary MUST retain synthetic probe attention when other endpoints succeed and MUST require affirmative connectivity evidence before it reports healthy connectivity.

#### Scenario: Only a TCP stress failure is present
- **WHEN** the report has no healthy connectivity result
- **THEN** the summary does not report healthy connectivity
