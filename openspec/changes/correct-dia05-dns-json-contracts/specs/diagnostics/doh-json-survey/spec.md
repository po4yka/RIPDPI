## Purpose

Correct DNS JSON response validation and evidence while keeping binary DoH and the selected provider panel separate.

## ADDED Requirements

### Requirement: REQ-DGN-1794732375813278-001 — Bind DNS JSON answers

The parser MUST bind each usable address to the requested question name and type, require valid RR names, types and IP families, and follow only a valid CNAME chain. It MUST reject truncated or malformed relevant data.

#### Scenario: Bind DNS JSON answers

- **WHEN** An endpoint returns another question or an address without a record type
- **THEN** the survey does not mark the resolver as answered

### Requirement: REQ-DGN-1794732375813278-002 — Preserve DNS response semantics

The scheduled and library surveys MUST distinguish NODATA, NXDOMAIN, DNS server RCODE errors and malformed JSON. A negative DNS result without a positive control MUST remain inconclusive.

#### Scenario: Preserve DNS response semantics

- **WHEN** A valid HTTP 200 response contains NOERROR without A data, NXDOMAIN or SERVFAIL
- **THEN** each result retains its distinct DNS meaning and is not a fabricated transport or syntax failure

### Requirement: REQ-DGN-1794732375813278-003 — Preserve transport timeout kinds

The JSON HTTP path MUST preserve I/O error kind and stage. A blocking socket read timeout reported as WouldBlock or TimedOut MUST be a timeout. Cancellation and scan deadline MUST retain administrative meaning.

#### Scenario: Preserve transport timeout kinds

- **WHEN** A connected local peer sends no HTTP response until the read timeout expires
- **THEN** the scheduled provider result is timeout, not a generic network failure

### Requirement: REQ-DGN-1794732375813278-004 — Preserve unknown analytics

Connectivity analytics MUST apply failure, attention, inconclusive and healthy precedence in that order. An empty report MUST remain inconclusive.

#### Scenario: Preserve unknown analytics

- **WHEN** JSON survey is inconclusive and no probe reports failure or attention
- **THEN** the network verdict is inconclusive, including when another probe is healthy

### Requirement: REQ-DGN-1794732375813278-005 — Avoid unsupported blocking attribution

The library DoH survey MUST retain HTTP, TLS, timeout and response failures as inconclusive unless independent evidence establishes blocking.

#### Scenario: Avoid unsupported blocking attribution

- **WHEN** All library resolver results are HTTP 503 or transport failures
- **THEN** the probe does not report all resolvers blocked

### Requirement: REQ-DGN-1794732375813278-006 — Build vendor query parameters safely

Both library runners MUST preserve endpoint query parameters and encode the requested name as one query value. Unsupported endpoint forms MUST fail before network I/O.

#### Scenario: Build vendor query parameters safely

- **WHEN** An endpoint already has a query string and the requested name contains reserved characters
- **THEN** the actual GET contains one correct name and type parameter without changing the endpoint parameters

