## ADDED Requirements

### Requirement: REQ-DIA12-F1 — Preserve HTTPS lookup outcomes

The HTTPS readiness probe SHALL record DNS and transport outcomes and the source of the lookup. A platform exception SHALL complete its target without losing the outcomes of other targets. Cancellation SHALL stop the lookup and propagate to its caller.

#### Scenario: Compare a missing configuration with a system error

- **WHEN** one target gives a valid HTTPS RRset without ECH and the Android lookup for a different target fails
- **THEN** the probe reports both results and shows a lookup failure separately from the observed absence of ECH

### Requirement: REQ-DIA12-F2 — Use the applicable HTTPS service binding

The ECH readiness probe SHALL choose an applicable ServiceMode record with valid ECH configuration. It SHALL use the applicable dial endpoint, port, and TCP ALPN while it keeps the original service name for TLS authentication. Unsupported parameters or invalid configuration SHALL be visible if no applicable candidate remains.

#### Scenario: Test an alternative endpoint

- **WHEN** the selected record requires a different target and port and supports a TCP ALPN
- **THEN** the readiness test dials that target and port and authenticates the origin name
- **AND** a record that only permits HTTP/3 does not cause a TCP probe

### Requirement: REQ-DIA12-F3 — Limit DNS HTTP response bodies

The diagnostic HTTP clients SHALL accept the media types of their chosen DNS representation and apply a limit to decoded response bytes before they read the full body. A non-success HTTP status SHALL remain an HTTP error.

#### Scenario: Read a chunked binary response

- **WHEN** a success response claims to contain a DNS message
- **THEN** its decoded size is limited to 65535 bytes
- **AND** an unexpected media type or an over-limit body cannot become usable DNS evidence

### Requirement: REQ-DIA12-F4 — Validate vendor JSON evidence

The A-record JSON adapter SHALL validate the typed question, status, address, alias and negative-answer fields it uses. A SOA SHALL have a valid owner and RDATA before it can establish a negative TTL. Unknown provider fields MAY be ignored subject to the response limits.

#### Scenario: Reject invalid authority evidence

- **WHEN** a JSON response has a CNAME without a terminal A answer and an invalid SOA
- **THEN** the invalid SOA does not establish a NODATA result or a negative TTL

### Requirement: REQ-DIA12-F5 — Measure Extended DNS Errors with EDNS

The address diagnostic wire runners SHALL send an EDNS0 OPT record to receive EDE. The presentation SHALL distinguish a measured response without EDE from a path without EDE evidence.

#### Scenario: Observe EDE from an EDNS-aware responder

- **WHEN** a responder only returns EDE after it receives OPT
- **THEN** the live wire diagnostic can report its EDE code
- **AND** the vendor JSON lane without that evidence shows the value as unknown

### Requirement: REQ-DIA12-F6 — Stop expired and cancelled DNS exchanges

The Kotlin DNS network runners SHALL cancel a pending HTTP exchange or close a pending UDP socket when cancellation or its deadline occurs. Cancellation SHALL prevent further attempts.

#### Scenario: Cancel during response I/O

- **WHEN** a loopback server delays the body or a UDP reply
- **THEN** cancellation releases the exchange promptly and the runner starts no fallback

### Requirement: REQ-DIA12-F7 — Calibrate DNS comparisons

The diagnostic comparison SHALL distinguish exact, partial, unavailable, conflicting, and matching failure evidence. It SHALL keep the resolver source if available. Repeated addresses alone SHALL NOT establish that an ISP page was observed.

#### Scenario: Compare incomplete and shared responses

- **WHEN** one encrypted lane times out and the other agrees only by subnet
- **THEN** the comparison does not report full DNS agreement
- **AND** matching SERVFAIL responses remain failures
- **AND** a shared address alone does not produce an ISP page or DPI finding

### Requirement: REQ-DIA12-F8 — Preserve the address family of AAAA records

The wire adapter SHALL preserve the IPv6 family of AAAA records through address projection.

#### Scenario: Read a mapped address

- **WHEN** a valid AAAA record contains an IPv4-mapped IPv6 address
- **THEN** the app retains the 16-byte IPv6 address family

### Requirement: REQ-DIA12-F9 — Use the transport-specific query policy

Kotlin binary DoH runners SHALL send transaction ID zero and verify reply identity. Cleartext UDP diagnostic sizing SHALL use its private-use EDNS option and SHALL NOT send the standard encrypted DNS padding option.

#### Scenario: Compare outgoing DoH and sizing queries

- **WHEN** the adapter sends binary DoH and a separate cleartext payload-sizing request
- **THEN** the DoH ID is zero and the sizing request has the expected size and private-use option

### Requirement: REQ-DIA12-F10 — Keep DNS record decoding local

The Kotlin adapter SHALL preserve the effective TTL specified for DNS resource records and preserve the unsigned OPT control word. Strict validation SHALL be local to the adapter and SHALL NOT change dnsjava's global record factories.

#### Scenario: Parse a large TTL and an extended RCODE

- **WHEN** a resource record sets the TTL high bit and OPT contains a high extended RCODE
- **THEN** the effective resource record TTL is zero and the extended RCODE is retained
