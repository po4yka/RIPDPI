## Purpose

Keep HTTP and H3 diagnostic verdicts tied to valid, bounded protocol observations and specific blockpage evidence.

## ADDED Requirements

### Requirement: REQ-DIA03-H3 — Reject malformed H3 responses

The H3 client MUST reject connection-specific response fields and invalid pseudo-fields before a completed successful response verdict. It MUST preserve normal GET, error responses, trailers, certificate verification, path admission, and cancellation.

#### Scenario: Forbidden response field

- **WHEN** a trusted local H3 peer returns a forbidden connection-specific response field
- **THEN** the probe does not report COMPLETE or a healthy endpoint

#### Scenario: Invalid pseudo-fields

- **WHEN** a response repeats a pseudo-field, uses a request pseudo-field, or puts a pseudo-field after an ordinary field
- **THEN** the response is rejected as malformed

### Requirement: REQ-DIA03-LIMIT — Bound H3 received frame storage

The H3 stack MUST bound retained non-DATA frame input before it accumulates an oversized declared payload. DATA streaming and valid control messages MUST remain usable. Both the GET and PMTU callers MUST retain their deadlines and cancellation behavior.

#### Scenario: Oversized non-DATA frame

- **WHEN** a local peer declares a non-DATA frame beyond the accepted storage limit
- **THEN** the decoder rejects it or skips an unknown frame with bounded retained input

### Requirement: REQ-DIA03-CODING — Preserve HTTP content interpretation

The HTTP probe MUST request only supported content coding and MUST NOT report a clean response merely because it cannot interpret the response coding. Size and time limits MUST remain active.

#### Scenario: Encoded blockpage

- **WHEN** the same blockpage is sent as identity and as an unsupported coding
- **THEN** the encoded variant is detected or explicitly rejected and never becomes a clean success

### Requirement: REQ-DIA03-FINGERPRINT — Require specific blockpage evidence

The shared matcher MUST NOT confirm a blockpage from a generic phrase in an ordinary article alone. It MUST retain strong ISP fingerprints and preserve the distinction between weak text evidence and confirmed blocking without storing response content in reports.

#### Scenario: Ordinary court article

- **WHEN** a normal HTML article contains the phrase used by a weak legal-decision fingerprint
- **THEN** that phrase alone does not confirm a blockpage

#### Scenario: Known short ISP blockpage

- **WHEN** a known blockpage has specific ISP or blocking evidence
- **THEN** it remains detected
