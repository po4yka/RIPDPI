# tls-clienthello Specification

## Purpose
Correctly inspect and mutate TLS ClientHello messages without treating unrelated bytes as SNI or losing later records.

## Requirements

### Requirement: REQ-TLS-SNI-BOUNDS — Bound SNI extraction

The implementation MUST return an SNI hostname only within the declared name and extension vectors.

#### Scenario: Invalid SNI vector

- **WHEN** the hostname crosses an SNI vector boundary or has a non-host name type
- **THEN** inspection returns no hostname and does not mutate unrelated bytes

### Requirement: REQ-TLS-RECORDS — Inspect fragmented ClientHello

The implementation MUST expose the correct hostname to live consumers when a valid ClientHello spans records, while preserving the original stream.

#### Scenario: SNI spans records

- **WHEN** TLS record headers split a ClientHello or its hostname
- **THEN** hostname inspection excludes the record headers and consumers receive correct offsets or safe fallback

### Requirement: REQ-TLS-MUTATION — Preserve mutation boundaries

The implementation MUST preserve trailing records or reject mutation unchanged, and MUST keep pre_shared_key as the last extension.

#### Scenario: Tail or PSK present

- **WHEN** SNI mutation encounters later TLS records or a pre_shared_key extension
- **THEN** it does not silently discard the tail or place padding after pre_shared_key

### Requirement: REQ-TLS-ECH-ID — Preserve ECH request identity

The implementation MUST use a typed request identifier across Rust ECH request APIs while preserving the JNI Long contract and invalid identifier behavior.

#### Scenario: Invalid or released request

- **WHEN** a caller cancels or releases an invalid or already released request ID
- **THEN** the request registry handles the ID without affecting another request

### Requirement: REQ-TLS-FUZZ-LOCK — Keep the fuzz graph reproducible

The fuzz workspace MUST resolve its declared dependencies with its committed lockfile and locked Cargo commands.

#### Scenario: Fuzz smoke preparation

- **WHEN** CI resolves the fuzz workspace with --locked
- **THEN** it uses the committed dependency graph without modifying the lockfile

### Requirement: REQ-CI-TOR-SQLITE — Use a fixed bundled SQLite

The Tor directory cache MUST use a bundled SQLite version that fixes RUSTSEC-2026-0459 without disabling the security gate.

#### Scenario: Security gate resolves the Tor dependency graph

- **WHEN** the locked dependency graph is audited for known advisories
- **THEN** the Tor SQLite dependency is fixed and the existing gate stays enabled

### Requirement: REQ-CI-ECH-DIAGNOSTICS — Preserve diagnostics while passing static checks

ECH diagnostics MUST preserve cancellation, error propagation, and request cleanup while satisfying the existing static checks.

#### Scenario: Cancellation races with connection failure

- **WHEN** an ECH connection fails while its caller is cancelled
- **THEN** the request is released once and cancellation semantics are preserved

### Requirement: REQ-TLS-RANDREC-CONFIG — Validate native fragment settings

The native converter MUST normalize legacy defaults and then enforce count 2..16 and sizes 1..4096 with minimum no greater than maximum for primary and rotation chains.

#### Scenario: Invalid positive fragment settings

- **WHEN** native JSON declares count 17, size 4097, or minimum 80 and maximum 16
- **THEN** conversion fails before any payload is emitted

#### Scenario: Legacy default fields

- **WHEN** a tlsrandrec field is absent or nonpositive
- **THEN** the converter retains the existing default normalization before validation

### Requirement: REQ-TLS-PRELUDE-ENVELOPE — Require a handshake envelope

TLS prelude normalization MUST reject non-handshake content types and invalid major versions without changing the input. It MUST preserve supported record minor overrides and truncated fake templates.

#### Scenario: Non-handshake body resembles ClientHello

- **WHEN** a record with ContentType 0x17 contains ClientHello-like bytes
- **THEN** the prelude leaves the bytes unchanged and reports no applied TLS prelude

#### Scenario: Valid real or truncated fake input

- **WHEN** the input has the supported handshake envelope and satisfies the path's completeness rules
- **THEN** record framing preserves the handshake payload and trailing records

### Requirement: REQ-TLS-RANDREC-ARITHMETIC — Bound partition arithmetic

Random-record partition calculations MUST handle representable direct API size bounds without arithmetic overflow and without producing empty fragments.

#### Scenario: Large direct API size limit

- **WHEN** a feasible small tail uses a maximum size near the machine integer limit
- **THEN** partitioning terminates with a valid payload-preserving partition

### Requirement: REQ-TLS-RANDREC-ADAPTIVE — Preserve saved size bounds

Adaptive TLS random-record profiles MUST keep all tail fragment sizes within the saved minimum and maximum while retaining feasibility checks.

#### Scenario: Tight or wide profile

- **WHEN** an adaptive profile adjusts a configured tail layout
- **THEN** every emitted tail record remains within the saved size interval or the prelude safely skips an infeasible layout
