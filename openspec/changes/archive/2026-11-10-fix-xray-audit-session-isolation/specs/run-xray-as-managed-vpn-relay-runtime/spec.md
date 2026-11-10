## Purpose

Keep the managed Xray local UDP hop private to its authenticated session and make unavailable engine failure recoverable.

## ADDED Requirements

### Requirement: REQ-XRAY-UDP-SESSION — Authenticate each managed UDP association

The managed Xray inbound MUST accept UDP only through an authenticated private RUF1 association. The association MUST use a fresh unpredictable nonce, the announced loopback source endpoint and increasing request identifiers. Standard shared UDP ingress MUST NOT bypass this authority. Closing control or stopping the owning runtime MUST close the association and its dispatchers before cleanup reports success.

#### Scenario: Another loopback sender

- **WHEN** another source sends raw, wrong-nonce or replayed frames after a valid association starts
- **THEN** those frames cannot reach the outbound peer and valid session traffic still succeeds.

#### Scenario: Closed control

- **WHEN** control closes while the UDP source remains open
- **THEN** stale session frames cannot reach the outbound peer and another authenticated session can start.

#### Scenario: Stop with live control

- **WHEN** the runtime stops while a client keeps its private control connection open
- **THEN** every private UDP endpoint and dispatcher is closed before stop returns and stale traffic cannot be forwarded.

#### Scenario: Preserve existing payload routing

- **WHEN** the authenticated client sends ordinary or managed domain-and-pin frames
- **THEN** replies preserve the expected peer authority and existing limits and empty-local-datagram behavior remain intact.

### Requirement: REQ-XRAY-STUB-CLEANUP — Release unavailable runtime ownership

The unavailable Xray stub MUST report startup failure without leaving native ownership occupied. Cleanup MUST remain fail-closed for an actual linked runtime whose cleanup is not confirmed.

#### Scenario: Retry after unavailable engine

- **WHEN** actual stub startup fails and cleanup runs
- **THEN** the owner is free for a later provider session.

#### Scenario: Linked cleanup failure

- **WHEN** linked native cleanup fails
- **THEN** ownership remains held and overlapping starts remain rejected.
