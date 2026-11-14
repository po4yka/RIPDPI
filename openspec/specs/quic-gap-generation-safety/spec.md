# quic-gap-generation-safety Specification

## Purpose
Keep QUIC packet-number-gap decoys bounded and separate from evidence that the original application flow succeeded.

## Requirements

### Requirement: REQ-DPI23-BOUNDS — Bound gap generation

Native and DSL admission MUST reject negative counts. Kotlin model normalization MAY retain its existing conversion of negative model values to zero. Admission MUST reject a count above 64 or a chain whose total requested gap count exceeds 64. Direct model planning MUST emit at most 64 gap decoys per datagram and MUST preserve the original datagram once. Nonmatching activation filters MUST NOT consume the runtime budget.

#### Scenario: Oversized configured count

- **WHEN** Kotlin or native admission receives a gap count of 65 or i32::MAX
- **THEN** admission rejects the chain before packet generation

#### Scenario: Direct model caller bypasses admission

- **WHEN** a model contains oversized or repeated gap steps
- **THEN** planning emits no more than 64 gap decoys and preserves the original datagram

#### Scenario: Disabled or filtered step

- **WHEN** count is zero or the activation filter does not match
- **THEN** the step emits no decoys and leaves the runtime budget available

### Requirement: REQ-DPI23-CONTEXT — Use one fresh context for each series

Each generated gap series MUST use one fresh cryptographic connection context, stable within the series and distinct from other series. Packet numbers MUST remain 2, 4, 6 and increase by two. The original bytes, version and configured fake authority MUST retain their existing meaning. Entropy failure MUST emit no decoys and MUST preserve original forwarding.

#### Scenario: Repeated or concurrent activation

- **WHEN** two gap series are generated with the same input or different fake authorities
- **THEN** the series do not reuse the same Initial key/nonce pairs, and each series retains one context internally

#### Scenario: Supported versions

- **WHEN** a normalized v1 or v2 Initial activates a gap step
- **THEN** an independent decoder reads the intended packet numbers and authenticates each generated Initial

### Requirement: REQ-DPI23-RESPONSE — Separate decoy responses from real-flow success

A long-header response that does not match a known real client Initial context MUST NOT mark that flow answered, stop its timeout, or update success learning. UDP delivery MUST remain transparent. Valid correlated Version Negotiation, Retry, Initial and Handshake responses, short-header traffic and generic UDP MUST retain their supported behavior. Correlation state MUST remain local to the flow and MUST NOT expose identifiers in logs or persistent storage.

#### Scenario: Reply only to a decoy

- **WHEN** a loopback peer receives the actual decoy and original and replies only with a decoy-correlated Version Negotiation packet
- **THEN** the response bytes reach the client, but the real flow remains awaiting its response

#### Scenario: Real response follows

- **WHEN** a response matches the original client connection identifiers
- **THEN** the response reaches the client and the real flow can be marked answered

#### Scenario: Later outbound datagram

- **WHEN** a non-Initial outbound datagram follows the real Initial
- **THEN** response attribution retains the real Initial context without requiring a full QUIC endpoint

#### Scenario: Multiple real connections share one UDP flow

- **WHEN** a delayed response matches an earlier real Initial while another real Initial is pending
- **THEN** the earlier real context remains eligible for response attribution

#### Scenario: Correlation capacity is exceeded

- **WHEN** more than 64 distinct real Initial contexts occur in one flow
- **THEN** delivery continues, but ambiguous response evidence cannot produce success learning, failure learning or timeout-based route advancement
