## Purpose

Correctly inspect and mutate TLS ClientHello messages without treating unrelated bytes as SNI or losing later records.

## ADDED Requirements

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
