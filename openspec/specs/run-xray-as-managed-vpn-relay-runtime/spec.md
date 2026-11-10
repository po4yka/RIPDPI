# run-xray-as-managed-vpn-relay-runtime Specification

## Purpose
Define observable acceptance for the real managed Android Xray VPN relay runtime.

## Requirements

### Requirement: REQ-OUT-1786264762917107-001 — Native protection and DNS ownership

The runtime MUST install protection before native startup and DNS initialization. Protection denial MUST abort the real Go dial/listen operation. Callback registration MUST not accumulate service owners across restart.

#### Scenario: Denied socket

- **WHEN** the current VPN protection controller rejects a descriptor
- **THEN** no connection or datagram is delivered through that socket and the runtime reports a safe failure without logging profile secrets

### Requirement: REQ-OUT-1786264762917107-002 — Concrete readiness

Startup MUST verify the configured local SOCKS5 listener before handing TUN traffic to Xray. Cancellation or startup failure MUST request cleanup and retain native ownership until cleanup is confirmed.

#### Scenario: Listener unavailable

- **WHEN** native startup reports success but the configured listener is not accepting the expected SOCKS protocol
- **THEN** readiness fails within its deadline, TUN handoff is withheld, and cleanup remains owned

### Requirement: REQ-OUT-1786264762917107-003 — Bounded owned shutdown

Stop MUST bound the caller's wait independently of a blocking native call. A pending or failed stop MUST retain ownership, prohibit overlapping starts and never report Stopped or AlreadyStopped until cleanup is confirmed.

#### Scenario: Native stop hangs

- **WHEN** native stop is blocked past the caller deadline
- **THEN** stop returns a typed incomplete outcome before native completion, retains the session lease and refuses replacement; late completion can release only that lease

### Requirement: REQ-OUT-1786264762917107-004 — Truthful telemetry and exit supervision

Telemetry MUST expose version and lifecycle without secrets and MUST distinguish listener readiness from outbound reachability. Unexpected native exit MUST stop only its owning VPN session.

#### Scenario: Runtime exits after readiness

- **WHEN** an active Xray runtime exits after its listener was ready
- **THEN** the service reports a typed failure and tears down that session without affecting a newer provider generation

### Requirement: REQ-OUT-1786264762917107-005 — Real Android runtime acceptance

The shipping Android build MUST include the verified pinned patched libXray AAR. Acceptance MUST exercise the actual gomobile bridge on an isolated Android emulator with controlled loopback traffic, not only a fake bridge.

#### Scenario: Real runtime restart

- **WHEN** a valid profile starts, exchanges traffic with the controlled peer, stops, and starts again
- **THEN** the linked runtime delivers the expected bytes, releases its listener on clean stop and starts without stale protection callbacks; invalid config and denied protection fail safely

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
