# dpi20-contracts Specification

## Purpose
Keep QUIC header-version decoy work finite, preserve the configured version, and keep decoy responses separate from real UDP flow success.

## Requirements

### Requirement: REQ-DPI20-LIMIT finite decoy work

Configuration MUST reject a DPI-20 step count or aggregate chain count above 64. The public planner MUST bound active DPI-20 output to 64 datagrams, each at most 65535 bytes, even when a caller bypasses configuration validation. Zero count MUST remain a no-op. A normal plan MUST preserve the original datagram.

#### Scenario: Excess count
- **GIVEN** count 65 or repeated steps with total 65
- **WHEN** configuration is validated
- **THEN** validation rejects the configuration before packet allocation.

#### Scenario: Direct planner call
- **GIVEN** an unvalidated large count and a valid Initial
- **WHEN** the planner builds output
- **THEN** it emits at most 64 bounded decoys and preserves the original.

### Requirement: REQ-DPI20-VERSION selected UDP version

The JSON configuration path MUST retain the selected fake version in the UDP group. The emitter MUST place that version in bytes 1 through 4 in network byte order for v1 and v2 input. Ciphertext and tag MUST retain the synthetic seed values; this operation MUST NOT be described as valid version conversion.

#### Scenario: Custom version
- **GIVEN** a custom fake version and UDP desync enabled
- **WHEN** JSON is converted and a normal direct plan is emitted
- **THEN** the selected UDP group and captured decoy contain that version, and the original datagram is unchanged.

### Requirement: REQ-DPI20-RESPONSE real reply correlation

An unanswered, feedback-eligible QUIC flow MUST accept only replies that match one of its retained real Initial contexts for session progress and success. Later VN replies MUST NOT advance a confirmed QUIC flow. Unmatched replies MUST remain transparent and MUST NOT refresh its timeout. Generic UDP MUST keep existing feedback behavior. Context overflow MUST retain neutral forwarding and activity without route learning.

#### Scenario: Decoy VN only
- **GIVEN** a real Initial awaits a response, later non-Initial datagrams have been sent, and only a VN for another CID arrives
- **WHEN** the receive loop processes that VN
- **THEN** it forwards the bytes but keeps the real flow unanswered and eligible for timeout.

#### Scenario: Matching real response
- **GIVEN** a response matches the real Initial and passes existing packet validation
- **WHEN** it arrives
- **THEN** normal session and success handling proceeds.

#### Scenario: Generic UDP
- **GIVEN** no real QUIC Initial is tracked, including after a malformed datagram that resembles an Initial header
- **WHEN** a normal UDP reply arrives
- **THEN** existing forwarding and success behavior is preserved.

#### Scenario: Retained real context budget
- **GIVEN** a UDP session already retains 64 distinct valid Initial contexts
- **WHEN** the next distinct valid Initial is sent
- **THEN** feedback becomes ambiguous without evicting earlier contexts, positive and negative route learning stop, and UDP delivery and activity continue.
