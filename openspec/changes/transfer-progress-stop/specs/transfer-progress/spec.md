## Purpose

Record and display the measured course and stopping point of bounded diagnostic HTTP transfers without inferring a provider policy.

## ADDED Requirements

### Requirement: REQ-TRANSFER-BODY — Accurate bounded body evidence

The runner MUST count decoded HTTP body bytes, retain a bounded monotonic timeline and distinguish response completion from a measurement limit. Expected length and unobserved timing MUST remain unknown when not measured.

#### Scenario: Truncated declared body

- **WHEN** a server closes before its declared body length
- **THEN** the attempt retains received bytes, first/last progress and an early EOF reason without reporting a complete response.

#### Scenario: Unknown length or chunked body

- **WHEN** framing ends normally
- **THEN** the body is complete and protocol framing bytes are excluded from the body-byte count.

### Requirement: REQ-TRANSFER-LIVE — Live progress and interruption

The app MUST receive rate-limited progress while a throughput attempt runs. Cancellation, idle timeout and an absolute deadline MUST be bounded and distinct. Partial observations MUST survive termination.

#### Scenario: Cancellation after progress

- **WHEN** the user cancels after some body bytes arrive
- **THEN** the final evidence retains the bytes and records cancellation rather than a network block.

### Requirement: REQ-TRANSFER-PRESENTATION — Readable current and historical evidence

The app MUST display received and expected bytes, first/last progress, elapsed time, stop reason and bounded timeline in live or completed views as applicable. A changed or unverified network scope MUST be visible without inventing a stop cause.

#### Scenario: Own byte budget

- **WHEN** the configured window is reached before the HTTP response is complete
- **THEN** the UI shows a measurement limit rather than a server completion or provider restriction.

### Requirement: REQ-TRANSFER-STORAGE — Private compatible storage and export

The app MUST retain numeric transfer evidence in existing local storage and explicit redacted exports. It MUST reject malformed or oversized evidence and preserve old progress/report payloads when the new optional field is absent. No response body or headers may be retained.

#### Scenario: Export and legacy data

- **WHEN** a report is exported or an old report is opened
- **THEN** new numeric evidence survives redaction and old reports remain readable without fabricated measurements.
