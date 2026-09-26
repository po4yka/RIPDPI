## Purpose

The diagnostics engine measures the JSON DoH resolver path for domains selected by the user and reports evidence that reflects completed HTTPS and DNS responses.

## ADDED Requirements

### Requirement: REQ-DGN-1790425188376122-001 — Survey selected targets

The Kotlin planner MUST select the DoH JSON family for supplied DNS targets. The engine MUST execute the stage in its existing default connectivity plan or when a scan selects the `DOH_JSON_SURVEY` family, and MUST query each distinct DNS target domain at most once per resolver.

#### Scenario: Selected target

- **WHEN** a connectivity scan selects the survey and supplies one DNS target
- **THEN** the stage requests that target from each configured JSON resolver and records one result for the target

#### Scenario: No DNS targets

- **WHEN** a connectivity scan supplies no DNS targets
- **THEN** the stage sends no JSON DoH requests

#### Scenario: Expanded resolver candidates

- **WHEN** a DNS domain appears more than once with different encrypted resolver candidates
- **THEN** the JSON survey queries each vendor resolver once for that domain

### Requirement: REQ-DGN-1790425188376122-002 — Report resolver evidence

The engine MUST preserve each resolver's HTTP, DNS answer, or transport result and MUST classify the aggregate result without treating an HTTP response or malformed JSON as a successful DNS answer.

#### Scenario: Mixed resolver responses

- **WHEN** one resolver returns a valid address and another fails
- **THEN** the result reports the successful address and the failed resolver separately, and the aggregate outcome is healthy

#### Scenario: No usable answer

- **WHEN** every resolver returns no usable address or fails
- **THEN** the aggregate outcome is not healthy and every resolver status remains visible

### Requirement: REQ-DGN-1790425188376122-003 — Bound network requests

The engine MUST use the scan's direct or proxied transport, verify HTTPS certificates, request JSON, encode the target as a query value, and stop starting requests after cancellation or deadline.

#### Scenario: Unsafe target characters

- **WHEN** a DNS target contains characters that could alter an HTTP request line or query parameters
- **THEN** those characters do not change the request structure or the measured target

#### Scenario: Interrupted survey

- **WHEN** the scan is cancelled or its deadline expires during a resolver survey
- **THEN** the stage starts no further resolver requests and does not report incomplete evidence as healthy

### Requirement: REQ-DGN-1790425188376122-004 — Preserve contracts

The engine MUST keep the existing JNI wire schema, persisted data model, and resolver runtime unchanged.

#### Scenario: Existing caller

- **WHEN** an existing scan supplies no DNS targets
- **THEN** its wire format and JSON DoH network behavior remain compatible
