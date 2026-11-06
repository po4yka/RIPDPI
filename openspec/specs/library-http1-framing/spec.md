# library-http1-framing Specification

## Purpose
Provide consistent, bounded HTTP/1 parsing while preserving Android transport ownership and diagnostic measurements.

## Requirements

### Requirement: REQ-HTTP1-001 — Complete and bounded message framing

The implementation MUST use maintained library framing for HTTP exchanges, process informational responses, and reject malformed framing or premature EOF. Application body limits MUST remain bounded.

#### Scenario: Delayed close-delimited response

- **WHEN** a server pauses after part of a body and later sends the remainder before EOF
- **THEN** the exchange returns the full body or an explicit timeout, never a successful truncated body.

#### Scenario: Invalid or ambiguous framing

- **WHEN** a client receives invalid chunk delimiters, conflicting content lengths, unsupported transfer coding, or Transfer-Encoding plus Content-Length
- **THEN** the exchange fails and does not publish a complete successful response.

#### Scenario: Ingress request has both framing headers

- **WHEN** an ingress request has Transfer-Encoding plus Content-Length
- **THEN** the library removes Content-Length, handles at most one request, closes the connection, and does not forward either raw framing header in the relay JSON.

### Requirement: REQ-HTTP1-002 — Preserve transport and measurement ownership

The implementation MUST preserve protected connections, TLS settings, exact diagnostic request bytes, partial measurements, body budgets, and cancellation ownership.

#### Scenario: Diagnostic probe with unusual request bytes

- **WHEN** a diagnostic probe sends a deliberately unusual request and receives a partial response
- **THEN** the request bytes remain unchanged and the measured bytes remain distinct from response completion.

#### Scenario: Cancel an exchange

- **WHEN** the owner cancels or times out an exchange
- **THEN** the connection driver stops with the owner and does not retain a detached socket task.

### Requirement: REQ-HTTP1-003 — Parse upgrade status and tokens

The implementation MUST validate upgrade status codes and header tokens with a library parser while preserving the exact outbound request and all tunnel bytes after the response head.

#### Scenario: Alternate reason phrase

- **WHEN** a valid HTTP/1.1 response uses status 101 with an alternate reason phrase and the required upgrade tokens
- **THEN** the tunnel accepts the upgrade and retains bytes after the response headers.
