## Purpose

Show whether QUIC Initial responses differ by version or by earlier packets on the same flow, without claiming handshake or application reachability.

## ADDED Requirements

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-VERSION — Version

The probe MUST build v1 and v2 Initials with the `ripdpi-packets` builders, and the v2 key derivation MUST pass the RFC 9369 Appendix A known-answer vectors. Unknown-version packets MUST NOT count as a v2 test.

#### Scenario: Endpoint without v2

- **WHEN** the endpoint answers the v2 Initial with Version Negotiation only
- **THEN** v2 is recorded as unsupported by the endpoint, not as blocked and not as a success

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-ISOLATION — Isolation

Each matrix cell MUST use a fresh socket on a new local port, and the result MUST record the execution order. The same-flow residual cell MUST run only when the user enables it and MUST be reported apart from the matrix. The result MUST state that local port separation does not prove separate NAT mappings.

#### Scenario: Residual cell disabled

- **WHEN** the user runs the matrix without enabling the residual cell
- **THEN** no two cells share a socket

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-CONTROL — Control

The probe MUST run control and test SNI cells against one user-authorized endpoint with bounded repetitions, bytes, and deadline. A version difference MUST be reported only when both control cells get a valid Initial response.

#### Scenario: Control fails

- **WHEN** a control cell times out
- **THEN** the result is inconclusive and does not say that QUIC is blocked

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-CLAIMS — Claims

The result MUST report Initial-stage evidence only and MUST NOT present a valid Initial response as endpoint authentication, handshake success, or HTTP/3 success. A version difference MUST NOT change relay or strategy settings.

#### Scenario: v1 blocked, v2 answered

- **WHEN** the test SNI times out on v1 and gets a valid Initial response on v2, and both controls succeed
- **THEN** the result shows a version difference at the Initial stage, and strategy settings are unchanged
