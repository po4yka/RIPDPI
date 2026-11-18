## Purpose

Provide bounded reproducible network comparison evidence without overstating censorship attribution or exposing private network identities.

## ADDED Requirements

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-VERSION — Version

The implementation MUST construct valid integrity-protected QUIC v1 and v2 Initials with matching SNI/control intent and independent known-answer fixtures, using the correct version-specific salts and packet semantics. Unknown-version decoys MUST NOT count as a v2 test.

#### Scenario: Version evidence

- **WHEN** a receiver does not support v2 or only sends Version Negotiation
- **THEN** record protocol unsupported or partial evidence, never successful circumvention

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-ISOLATION — Isolation

The implementation MUST use fresh sockets and disjoint local UDP ports for independent matrix cells, record execution order and isolate any deliberate same-flow residual experiment as opt-in. Local port separation does not prove distinct external NAT tuples.

#### Scenario: Isolation evidence

- **WHEN** NAT mapping is unobservable or a prior flow may contaminate a run
- **THEN** include the isolation limit and do not assert a clean causal comparison

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-CONTROL — Control

The implementation MUST compare control/test names against an authorized known-capable endpoint with bounded repetitions, bytes and cancellation. Require a valid control for inference and keep network and transport scope attached.

#### Scenario: Control evidence

- **WHEN** both versions or both controls time out
- **THEN** return inconclusive rather than label QUIC broadly blocked

### Requirement: REQ-QUIC-VERSION-STATE-CONTROLS-CLAIMS — Claims

The implementation MUST separate Initial response, handshake, and verified application payload evidence. A version difference is a diagnostic observation and MUST NOT automatically change production relay settings.

#### Scenario: Claims evidence

- **WHEN** only Initial-level v2 evidence exists
- **THEN** display that limited stage and no working-VPN or HTTP/3 success claim
