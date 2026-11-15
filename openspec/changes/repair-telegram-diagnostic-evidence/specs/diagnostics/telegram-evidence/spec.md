## ADDED Requirements

### Requirement: REQ-DIA08-HTTP Telegram HTTP evidence comes from verified peers

The diagnostic SHALL bound the transfer, use verified HTTPS peers, validate the
HTTP response, and report page reachability separately from media throughput.
It SHALL reject invalid targets before related network I/O.

#### Scenario: The upload peer uses a certificate for another identity

- **WHEN** TLS cannot authenticate the configured HTTPS authority
- **THEN** the diagnostic records a verification failure and does not send the upload

#### Scenario: The page returns a short success response

- **WHEN** a configured website check receives a complete valid HTTP success response
- **THEN** the diagnostic reports page reachability without classifying it as a slow media download

### Requirement: REQ-DIA08-PATH Telegram TCP and WSS evidence name the measured path

The diagnostic SHALL report each explicit DC endpoint, family, port, and
connection outcome. The WSS result SHALL describe an opening handshake. TCP
and WSS success SHALL carry no claim about MTProto application exchange.

#### Scenario: A DC port fails

- **WHEN** one configured port fails and another port succeeds
- **THEN** the report preserves both outcomes and identifies the dial or proxy path

### Requirement: REQ-DIA08-ABORT Telegram interruptions preserve completed evidence

The diagnostic SHALL preserve completed results and known progress, stop new
network operations, and distinguish cancellation, deadline, and network failure.

#### Scenario: A peer responds after cancellation

- **WHEN** cancellation reaches the probe before the response
- **THEN** the response cannot produce a success and completed measurements remain available

### Requirement: REQ-DIA08-REPORT Telegram availability reflects coverage and outcome

The diagnostic SHALL treat failures and missing required measurements as distinct
from healthy measurements, and SHALL preserve that distinction in the report,
summary card, and share text. A score is available only for completed comparable
measurements.

#### Scenario: HTTP and WSS succeed while all DC attempts fail

- **WHEN** the diagnostic finalizes the mixed result
- **THEN** the summary does not report healthy and the report shows the DC failures
