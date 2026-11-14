## Purpose

Keep QUIC CRYPTO split preludes bounded and preserve the original client data and explicit configuration policy.

## ADDED Requirements

### Requirement: REQ-QUIC-ORIGINAL — Preserve original UDP sends

An optional prelude MUST NOT suppress a usable original payload when its size exceeds the input budget or its send fails with a local size or buffer error. Fallback receipts MUST NOT report Applied. Other flow/network send errors remain terminal.

#### Scenario: Preserve original UDP sends

- **WHEN** a prelude exceeds the budget or has a local size or buffer error before the original write
- **THEN** the original is sent once with the known normal TTL, or a TTL restoration or original-send error is reported with accurate counters

### Requirement: REQ-QUIC-TTL — Preserve fixed TTL policy

The generated UDP group MUST retain the configured fixed fake TTL.

#### Scenario: Preserve fixed TTL policy

- **WHEN** UI configuration selects a CRYPTO split and fixed TTL 23
- **THEN** the selected UDP group and actual prelude sender use TTL 23

### Requirement: REQ-QUIC-OVERLAP — Reject conflicting CRYPTO overlap

The normalizer MUST reject conflicting bytes at a covered CRYPTO offset and MUST preserve identical overlaps and frame order independence.

#### Scenario: Reject conflicting CRYPTO overlap

- **WHEN** authenticated CRYPTO frames disagree at one offset
- **THEN** no split prelude is generated and the original remains unchanged

### Requirement: REQ-QUIC-COUNT — Bound split counts

Kotlin and Rust parsing MUST accept only counts from zero through 64 and a combined CRYPTO split count at most 64. The typed planner MUST enforce the same aggregate output quota.

#### Scenario: Bound split counts

- **WHEN** a configuration or typed model contains huge or repeated counts
- **THEN** parsing rejects excess counts or the planner safely limits output before cloning packets

### Requirement: REQ-QUIC-MINIMUM — Reject short client Initial input

The CRYPTO split planner MUST NOT shape client Initial datagrams shorter than 1200 bytes. It MUST retain support for short packets coalesced inside a complete 1200-byte datagram.

#### Scenario: Reject short client Initial input

- **WHEN** a complete ClientHello is carried in a 300-byte datagram
- **THEN** only the unchanged original is sent without technique success

### Requirement: REQ-QUIC-SCOPE — Preserve the decoy boundary

The implementation MUST retain the original datagram and existing relay fallback. It MUST NOT claim external compatibility from local decoding. Initial bytes use public derivation inputs; no client traffic secrets are required.

#### Scenario: Upstream relay

- **WHEN** an upstream SOCKS relay is selected
- **THEN** only the original relay payload is sent and fallback evidence names skipped mutation.
