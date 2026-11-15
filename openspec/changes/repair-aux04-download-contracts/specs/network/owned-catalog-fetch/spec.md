## Purpose

Keep catalog delivery bounded and complete, stop canceled requests, and preserve response metadata for its consumers.

## ADDED Requirements

### Requirement: REQ-AUX04-F1 — Bound response reads before conversion

Download services MUST apply their body limits before Retrofit buffers success or error data. Geo release metadata MUST have a finite small limit.

#### Scenario: Oversized success and error bodies

- **WHEN** a catalog endpoint sends a known-length or chunked response above its limit with status 200 or 500
- **THEN** reading stops at the limit plus a bounded transport buffer and the response is closed without installation.

#### Scenario: Valid data at the limit

- **WHEN** a response has exactly the permitted bytes
- **THEN** the caller receives those exact bytes and existing signature checks remain required.

### Requirement: REQ-AUX04-F2 — Reject incomplete TLS-delimited responses

Native fetch MUST reject a close-delimited HTTPS body without a valid TLS closure alert. It MUST retain successful handling of complete length-delimited and chunked responses.

#### Scenario: Abrupt close without HTTP framing

- **WHEN** the peer closes TCP without close_notify after an unframed response prefix
- **THEN** native fetch reports an incomplete response and does not expose the prefix as a successful body.

#### Scenario: Complete framed body

- **WHEN** the full Content-Length or terminal chunk has arrived before an incomplete TLS close
- **THEN** the complete HTTP response remains usable.

### Requirement: REQ-AUX04-F3 — Cancel owned HTTP requests

Kotlin cancellation MUST signal the native HTTP request, stop its pending I/O, and release its request handle on all completion paths. A late cancellation MUST NOT affect another request.

#### Scenario: Cancellation during a stalled body

- **WHEN** a caller cancels an owned HTTP request whose server holds the body open
- **THEN** the native socket closes within the local acceptance budget, no later redirect is requested, and a new request can proceed.

#### Scenario: Cancellation before execution

- **WHEN** a request handle is canceled before execution starts
- **THEN** execution performs no network I/O and returns cancellation.

### Requirement: REQ-AUX04-F4 — Preserve response media type

Native fetch MUST pass Content-Type to its Kotlin consumer. Browser fallback MUST use the response charset and distinguish binary data from text.

#### Scenario: Native text charset and binary response

- **WHEN** native fetch returns valid Windows-1251 text or an application/octet-stream body
- **THEN** the browser decodes the text without replacement characters and uses its existing binary response behavior.
