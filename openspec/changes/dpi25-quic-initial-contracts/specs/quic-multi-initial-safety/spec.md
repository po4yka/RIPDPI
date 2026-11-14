## Purpose

Make the DPI-25 synthetic QUIC prelude protocol-correct and bounded while keeping the real datagram intact.

## ADDED Requirements

### Requirement: REQ-DPI25-SEED — Correct synthetic QUIC seed

The implementation MUST emit empty TLS compatibility session IDs, h3 ALPN, TLS 1.3, and QUIC transport parameters with initial_source_connection_id equal to the packet SCID for both profiles and QUIC v1/v2.

#### Scenario: Independent receiver reads either profile

- **WHEN** either synthetic profile is decoded by an independent QUIC receiver
- **THEN** it passes the ALPN and mandatory transport parameter checks.

### Requirement: REQ-DPI25-STATE — Independent connection state

Each emitted decoy MUST use a fresh DCID, SCID, TLS random and key share. Alternating profiles MUST be separate synthetic connections, each with packet number zero and one complete CRYPTO transcript.

#### Scenario: A burst is repeated

- **WHEN** the same valid input is planned twice
- **THEN** the decoys have independent connection IDs and do not reuse a key/nonce pair for different plaintext.

### Requirement: REQ-DPI25-TTL — Preserve UDP TTL settings

The selected UDP group MUST preserve fixed and adaptive fake TTL settings. The planner MUST prefer a resolved fake TTL, then the fixed TTL, then eight. The emitter MUST restore the normal TTL before the original datagram.

#### Scenario: User selects a fixed or resolved TTL

- **WHEN** the UI config sets fake TTL one, or the resolver returns three
- **THEN** the selected UDP plan uses one or three respectively and sends the original at the normal TTL.

### Requirement: REQ-DPI25-COUNT — Bound packet work

Kotlin and native config validation MUST reject per-step or aggregate DPI-25 counts above 32. A positive count MUST cost max(count,2) packets and zero MUST disable the step. The direct planner MUST reject an invalid internal budget before packet allocation by sending only the original.

#### Scenario: A hostile count or repeated steps exceed the budget

- **WHEN** a count is 33 or i32::MAX, or several steps exceed the aggregate budget
- **THEN** public config validation rejects it and an internal model causes original-only output.

### Requirement: REQ-DPI25-SIZE — Bound encoded size

Each decoy MUST fit the 1200-byte encoded datagram budget when no remote PMTU is known. Index padding MUST stay within that budget. A seed that cannot fit MUST be omitted without losing the original.

#### Scenario: A large local input does not prove remote PMTU

- **WHEN** the input is larger than 1200 or count is at its maximum
- **THEN** every emitted decoy stays within the budget and the original remains unchanged.

### Requirement: REQ-DPI25-FALLBACK — Keep activation and fallback contracts

The implementation MUST preserve count-zero, activation-filter and non-normalized-input original-only behavior. Upstream SOCKS UDP MUST continue to skip synthetic decoys. Frozen packet APIs MUST keep their existing outputs.

#### Scenario: The runtime cannot apply the prelude

- **WHEN** input normalization fails, activation excludes the step, or a SOCKS relay owns egress
- **THEN** the real datagram is preserved and no unsupported packet mutation is claimed.
