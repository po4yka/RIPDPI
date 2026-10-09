## Purpose

Give users one evidence-based view of the connection stages reached by each diagnostic attempt.

## ADDED Requirements

### Requirement: REQ-STAGE-PROJECTION — Preserve measured stage boundaries

The implementation MUST project supported probe evidence into a fixed stage vocabulary. It MUST keep independent attempts and protocol branches separate. It MUST distinguish unknown, not applicable, not reached, succeeded, observed, failed, partial, running, cancelled and timed-out states. Missing or malformed evidence MUST NOT become success.

#### Scenario: Independent domain checks

- **WHEN** a domain has a successful TLS check and a failed separate plaintext HTTP check
- **THEN** separate lanes retain both facts without combining them into one connection.

#### Scenario: Limited evidence

- **WHEN** QUIC Initial, an HTTP 403 or a pinned IP is observed
- **THEN** the scale describes the QUIC response, received HTTP headers or omitted DNS respectively, without claiming HTTP/3 success or DNS resolution.

#### Scenario: Interrupted transfer

- **WHEN** a transfer is cancelled or times out after receiving bytes
- **THEN** its attempt retains body progress and interruption state, without a provider-block claim.

### Requirement: REQ-STAGE-UI — Use one localized scale across results

The application MUST show the same projection in current results and historical result details, and MUST show available live transfer stages. All labels MUST exist in all ten locales. State MUST remain understandable without color.

#### Scenario: Narrow and RTL layouts

- **WHEN** a user opens stages on a narrow screen or with RTL and enlarged text
- **THEN** labels, state and available numeric evidence remain readable and can be expanded.

### Requirement: REQ-STAGE-EXPORT — Preserve safe and compatible evidence

Text exports MUST use the same stage projection as the UI and MUST emit only known stage and lane identifiers, states and validated numeric values. Existing JSON and CSV source evidence MUST remain readable without a schema migration. Stage projection MUST NOT expose response payloads or identifiers absent from the current redacted export.

#### Scenario: Legacy and hostile detail fields

- **WHEN** old reports lack stage fields or contain invalid numeric values or duplicate evidence keys
- **THEN** projection remains conservative and export does not copy arbitrary detail text into stage lines.
