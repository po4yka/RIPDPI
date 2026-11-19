## Purpose

Provide one failure-stage vocabulary shared by Rust and Kotlin, vendored contract conformance, and typed, non-silent error handling on the diagnostics data path.

## ADDED Requirements

### Requirement: REQ-DGN-1795085357320979-001 — One failure-stage enum in both languages

The implementation MUST define `failure_stage` with exactly the values dns, tcp_connect, tcp_reset, tls_handshake, reality_auth, quic_handshake, proxy_auth, protocol_error, idle_timeout, throttled, unknown in Rust and Kotlin, serialized identically.

#### Scenario: Both enums match the vendored schema

- **WHEN** the Rust and Kotlin contract tests read the vendored `client-field-report` schema
- **THEN** both enum value sets equal the schema's `failure_stage` enum

### Requirement: REQ-DGN-1795085357320979-002 — Total mapping from probe and connection failures

The implementation MUST map every `DpiProbeError`, every `ProbeStage`, and each connection-path failure to exactly one `failure_stage`, and MUST map any unmapped case to `unknown`.

#### Scenario: Every DpiProbeError maps

- **WHEN** a test enumerates all `DpiProbeError` subclasses
- **THEN** each yields a stage and no subclass throws or is skipped

#### Scenario: TLS reset maps to tcp_reset or tls_handshake as specified

- **WHEN** `DpiProbeError.TlsRst` and `DpiProbeError.TcpRst` are mapped
- **THEN** the results equal the stages listed in the design mapping table

### Requirement: REQ-DGN-1795085357320979-003 — No text-matching classification

The implementation MUST NOT derive a failure class or stage from the content of an error message.

#### Scenario: Message text does not change the stage

- **WHEN** two failures carry the same typed cause and different message texts
- **THEN** both map to the same stage

### Requirement: REQ-DGN-1795085357320979-004 — Export data path does not swallow errors

The implementation MUST log a failure of developer analytics collection in the diagnostics export and return a typed outcome that records the failure, and MUST rethrow cancellation.

#### Scenario: Collection failure is visible

- **WHEN** `developerAnalyticsSource.collect` throws a non-cancellation exception during export
- **THEN** the exporter logs it and the payload carries a typed failure outcome, not an indistinguishable empty payload

#### Scenario: Cancellation propagates

- **WHEN** collection is cancelled
- **THEN** the exporter rethrows the cancellation

### Requirement: REQ-DGN-1795085357320979-005 — Contract tests use vendored fixtures with a drift pin

The implementation MUST test against byte-identical vendored `client-field-report` v1 fixtures, and the tests MUST fail when the vendored contract version differs from the supported version.

#### Scenario: Valid and invalid fixtures

- **WHEN** the parser reads vendored valid and negative fixtures
- **THEN** valid fixtures parse and negative fixtures (unknown stage, missing failure_stage on failure) are rejected
