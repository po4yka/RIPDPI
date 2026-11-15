## Purpose

Keep DNS and public-IP diagnostics accurate when data is partial, unavailable, or collected through different network paths.

## ADDED Requirements

### Requirement: REQ-DIA18-F01 — Respect the network request setting

The implementation MUST suppress all active DIA-18 reflection, GeoIP, and ASN requests when network requests are disabled, while preserving local inventory.

#### Scenario: Respect the network request setting

- **WHEN** network requests are disabled in either entry point
- **THEN** controlled network sinks receive no requests.

### Requirement: REQ-DIA18-F02 — Preserve address families

The implementation MUST compare IP observations within the same address family through final classification.

#### Scenario: Preserve address families

- **WHEN** both groups return the same IPv4 and IPv6 pair or have no common family
- **THEN** ordinary dual-stack data is not classified as a mismatch.

### Requirement: REQ-DIA18-F03 — Compare numeric address identity

The implementation MUST compare valid numeric addresses by canonical identity.

#### Scenario: Compare numeric address identity

- **WHEN** two services spell the same IPv6 address in different valid forms
- **THEN** the addresses compare equal.

### Requirement: REQ-DIA18-F04 — Validate provider capabilities and identity

The implementation MUST reject malformed or mismatched provider responses and preserve absent traits as unknown.

#### Scenario: Validate provider capabilities and identity

- **WHEN** a response omits security traits or its IP differs from the requested address
- **THEN** it cannot add a negative trait vote or a valid identity opinion.

### Requirement: REQ-DIA18-F05 — Preserve country uncertainty

The implementation MUST require sufficient agreeing country opinions before a country finding can drive detection.

#### Scenario: Preserve country uncertainty

- **WHEN** only one provider returns a foreign country or a country vote is tied
- **THEN** the result remains inconclusive rather than a hard country detection.

### Requirement: REQ-DIA18-F06 — Preserve resolver context

The implementation MUST use the selected resolver and network binding for endpoint resolution and ASN enrichment.

#### Scenario: Preserve resolver context

- **WHEN** system DNS fails but a configured resolver has a valid answer
- **THEN** the configured endpoint probe can run and its evidence names that context.

### Requirement: REQ-DIA18-F07 — Distinguish IPv6 failures

The implementation MUST report HTTP, TLS, and payload failures as errors rather than missing IPv6 support.

#### Scenario: Distinguish IPv6 failures

- **WHEN** an IPv6 loopback server returns HTTP 500
- **THEN** the endpoint result is ERROR.

### Requirement: REQ-DIA18-F08 — Show execution state

The implementation MUST distinguish observations, missing data, skipped checks, and errors from completed negative measurements in UI and exports.

#### Scenario: Show execution state

- **WHEN** DNS settings are observed or an active probe fails
- **THEN** its state is not presented as a successful leak test.

### Requirement: REQ-DIA18-F09 — Validate reflected IP payloads

The implementation MUST accept only the documented numeric IP field or a complete plain numeric response and reject local or ambiguous payloads.

#### Scenario: Validate reflected IP payloads

- **WHEN** HTML contains an example loopback address before a client address
- **THEN** the example is not accepted as a reflected public IP.

### Requirement: REQ-DIA18-F10 — Cancel and bound HTTP reads

The implementation MUST cancel active HTTP body reads with the owning coroutine and enforce a decoded response size limit.

#### Scenario: Cancel and bound HTTP reads

- **WHEN** a controlled server delays or exceeds the response budget
- **THEN** cancellation closes the call or a bounded error is returned.

### Requirement: REQ-DIA18-F11 — Describe measured route provenance

The implementation MUST describe unbound observations as default-route data and MUST NOT claim an attested underlay or VPN bypass.

#### Scenario: Describe measured route provenance

- **WHEN** the app uses an ordinary connection while a VPN can be active
- **THEN** the result does not identify that observation as a proven underlying path.

### Requirement: REQ-DIA18-F12 — Preserve copied endpoint evidence

The implementation MUST include IP comparison and consensus evidence in the copied report with the same privacy policy as other output.

#### Scenario: Preserve copied endpoint evidence

- **WHEN** the user copies a result with failed or mismatched endpoints
- **THEN** endpoint statuses, DNS answers, reflected addresses, and reasons remain available.

