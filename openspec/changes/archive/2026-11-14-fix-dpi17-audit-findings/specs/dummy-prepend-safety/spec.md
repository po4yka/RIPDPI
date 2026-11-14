## Purpose

Bound local resource use and keep the DummyPrepend user and packet contracts consistent.

## ADDED Requirements

### Requirement: REQ-DUMMY-COUNT — Bound counts

Kotlin and native JSON validation MUST reject a DummyPrepend step count above 64 or total count above 64 across a UDP chain. Direct-model planning MUST limit attempts to 64 per chain.

#### Scenario: Excessive count

- **WHEN** a configuration exceeds either count limit
- **THEN** validation rejects it and direct-model planning remains bounded.

### Requirement: REQ-DUMMY-BYTES — Bound allocation

The builder MUST check effective fake-host length before mutation, skip hosts above 4096 bytes and emit only packets of at most 4096 bytes.

#### Scenario: Oversized host

- **WHEN** an internal host exceeds 4096 bytes
- **THEN** the prelude is skipped and the original remains.

### Requirement: REQ-DUMMY-COMPAT — Preserve existing scope

The change MUST retain count-zero, unparsed-input and activation no-op behavior, rounds, compact representation, fake authority separation and the separate unmodified original.

#### Scenario: No applicable prelude

- **WHEN** count is zero, input is unparsed, or activation does not match
- **THEN** no Dummy packet is added and the original stays unchanged.

### Requirement: REQ-DUMMY-UI — State the prerequisite

All ten locale descriptions MUST describe parsed QUIC Initial datagrams rather than non-QUIC packets.

#### Scenario: User reads the description

- **WHEN** the description appears in a supported locale
- **THEN** it states the parsed-QUIC-Initial prerequisite.

### Requirement: REQ-DUMMY-WIRE — Verify the emitted contract

The enabled regression MUST check the current QUIC representation and fake authority through production loopback UDP and compare the received original with source bytes.

#### Scenario: Enabled regression

- **WHEN** RIPDPI_RUN_WIRE_E2E is 1
- **THEN** the test checks current representation and the original instead of the obsolete 64-byte packet.

### Requirement: REQ-DUMMY-ACCEPT — Complete acceptance before closure

The change MUST pass owning Kotlin/JVM tests, app and service Android lint, CI and CodeQL before closure. Build compatibility repairs MUST retain compiler and security checks.

#### Scenario: Final validation

- **WHEN** the completion commit is checked
- **THEN** exact-SHA evidence records successful local and hosted checks before the task is closed.

### Requirement: REQ-DUMMY-ASSET — Preserve checked asset provenance

The OBFS4 build MUST apply its reviewed patch only to pinned source and record patch provenance in generated and prebuilt metadata. It MUST reject wrong commits, preimages, patch digests and stale receipts, and keep Go linkname checks enabled.

#### Scenario: Verified OBFS4 asset

- **WHEN** Lyrebird is prepared for the packaged OBFS4 binary
- **THEN** only obfs4 is registered and all supported ABI builds pass with linkname checks enabled, while separate transport assets remain available.
