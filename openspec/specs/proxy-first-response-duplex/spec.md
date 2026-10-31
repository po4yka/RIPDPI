# proxy-first-response-duplex Specification

## Purpose
Keep bounded request and response forwarding active while the shared proxy observes
the first response, without replaying request bytes that it no longer retains.

## Requirements

### Requirement: REQ-PROXY-DUPLEX — Forward request continuation

The proxy MUST forward client continuation with backpressure while its first-response
observer is active. It MUST NOT require a server response before forwarding the
remaining request body or buffer an unbounded body for replay.

#### Scenario: Server waits for the complete upload

- **WHEN** autolearn is enabled and a client sends headers followed by a 1 MiB POST body
- **THEN** an origin that waits for the complete body receives its exact length and digest
- **AND** the client receives the completed response without disabling observation or extending a timeout

#### Scenario: Early response under upload backpressure

- **WHEN** an origin sends an early response while a large upload is backpressured
- **THEN** the response can reach the client while outbound forwarding remains bounded
- **AND** neither direction waits for the other direction to finish first

### Requirement: REQ-PROXY-NO-CONTINUATION-REPLAY — End replay eligibility

The proxy MUST end reconnect and replay eligibility before continuation handoff.
No later failure or classification result may resend that request on another
upstream. Existing first-payload-only retry rules MUST remain enforced.

#### Scenario: Failure after continuation handoff

- **WHEN** an upstream fails after the flow enters continuation forwarding
- **THEN** no alternate upstream receives a replay of the request or its body
- **AND** the flow uses its terminal failure policy with bounded cleanup

#### Scenario: Retry before continuation

- **WHEN** a retry-eligible first payload fails before continuation handoff
- **THEN** existing bounded retry and replay behavior remains available

### Requirement: REQ-PROXY-RESPONSE-OBSERVATION — Preserve response handling

Portable and io_uring paths MUST preserve first-response classification and
autolearn evidence. Accepted response bytes MUST reach the client exactly once
and in order. Existing TLS collection bounds and privacy rules MUST remain intact.

#### Scenario: Fragmented first response

- **WHEN** the first response arrives in fragments during request continuation
- **THEN** the existing bounded classifier observes the same response bytes
- **AND** accepted bytes are neither lost nor duplicated during the handoff

### Requirement: REQ-PROXY-DUPLEX-CLEANUP — Preserve lifecycle cleanup

The continuation path MUST preserve half-close, cancellation and shutdown
semantics. It MUST release owned sockets and tasks without retaining request
bodies or emitting their contents in diagnostics.

#### Scenario: Cancellation while waiting for the first response

- **WHEN** the client resets the connection or the owner stops the relay during first-response observation
- **THEN** both forwarding directions terminate through the existing lifecycle
- **AND** no owned socket or worker survives cancellation

#### Scenario: Owner stops an upload after an early response and peer FIN

- **WHEN** the origin finishes its response but keeps its read half open without draining the upload, and the owner stops the proxy
- **THEN** the blocked sender and both relay directions finish within the existing shutdown budget
- **AND** real client-finished telemetry confirms handler completion before the test client or origin is closed
- **AND** normal peer FIN without owner cancellation does not discard a valid remaining upload
