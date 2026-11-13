## Purpose

Keep the first plaintext ClientHello unchanged after TLS record reassembly while applying bounded, accurately reported record boundaries.

## ADDED Requirements

### Requirement: REQ-TLSREC-OFFSET — Apply one coordinate conversion

The prelude MUST apply adaptive boundaries in payload coordinates without a second record header correction.

#### Scenario: Adaptive semantic boundary

- **WHEN** a complete ClientHello and a segment budget select an SNI boundary
- **THEN** the emitted record length equals that independently known payload offset and the receipt reports an applied prelude

#### Scenario: Adaptive fallback boundary

- **WHEN** no semantic candidate fits and the resolver selects its payload target
- **THEN** the emitted boundary equals that target without a header correction

### Requirement: REQ-TLSREC-BUDGET — Bound repeat work

The prelude MUST reject repeat requests above a documented finite per-step work limit before it changes state. Existing runtime planner failure handling SHALL send the original effective payload.

#### Scenario: Excessive repeat request

- **WHEN** the repeat count exceeds the work limit
- **THEN** planning returns an error before a partial record transformation can be emitted

#### Scenario: Useful step after an unchanged boundary

- **WHEN** a repeated zero or duplicate position is followed by a useful skipped position within the limit
- **THEN** the later position is preserved and no empty record is emitted

### Requirement: REQ-TLSREC-MULTIRECORD — Preserve complete ClientHello records

For inputs that fit the existing synthetic record length representation, the prelude MUST resolve supported semantic markers on complete ClientHello messages across plaintext handshake records. Inputs above that limit SHALL remain unchanged. It MUST preserve the reassembled handshake bytes and all following wire bytes. Existing input record boundaries SHALL remain unless a configured record operation replaces them.

#### Scenario: Handshake fields or SNI cross records

- **WHEN** a complete ClientHello spans records at its header, vectors, SNI, or ECH extension
- **THEN** a valid selected boundary is applied and independent reassembly equals the original handshake bytes

#### Scenario: Incomplete contributing record

- **WHEN** a real stream lacks bytes from a record needed to contain the ClientHello
- **THEN** the prelude leaves the input unchanged and does not report an applied split

#### Scenario: Following record or coalesced data

- **WHEN** ClientHello records are followed by another handshake record, CCS, or early data
- **THEN** all following record bytes remain unchanged

### Requirement: REQ-TLSREC-ECH — Use span-aware activation metadata

ECH activation MUST use the visible outer ClientHello extension layout even when the SNI spans records. The change MUST NOT decrypt, log, or export private handshake content.

#### Scenario: ECH with split SNI

- **WHEN** the parsed outer ClientHello contains ECH and its SNI crosses a record boundary
- **THEN** the ECH activation gate sees ECH and transformation preserves the opaque extension bytes

#### Scenario: No ECH

- **WHEN** a ClientHello has no ECH extension
- **THEN** an ECH-required gate stays inactive

### Requirement: REQ-TLSREC-EVIDENCE — Require actual application in regressions

Adaptive tests MUST use complete valid inputs, assert applied prelude counts, and check independent record boundaries and reassembly bytes.

#### Scenario: A no-op plan cannot pass as a split

- **WHEN** a regression expects a record split
- **THEN** unchanged output or zero applied count fails the test
