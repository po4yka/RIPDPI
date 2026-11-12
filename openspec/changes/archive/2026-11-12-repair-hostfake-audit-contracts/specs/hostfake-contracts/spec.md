## Purpose

Keep HostFake packet emission, fallback results and saved Android configuration consistent while preserving real application bytes.

## ADDED Requirements

### Requirement: REQ-HOSTFAKE-FALLBACK — Report split fallback

The implementation MUST report effective split with a fallback reason after pre-emission raw refusal, retaining configured HostFake, every real byte and later chain steps.

#### Scenario: Report split fallback

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST report effective split with a fallback reason after pre-emission raw refusal, retaining configured HostFake, every real byte and later chain steps

### Requirement: REQ-HOSTFAKE-MD5 — Keep fake MD5 off originals

The implementation MUST add artificial MD5 only to fake ordered segments, with valid IPv4 and IPv6 checksums.

#### Scenario: Keep fake MD5 off originals

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST add artificial MD5 only to fake ordered segments, with valid IPv4 and IPv6 checksums

### Requirement: REQ-HOSTFAKE-SEQUENCE — Wrap TCP sequence

The implementation MUST add representable offsets modulo 2^32 and reject offsets above u32.

#### Scenario: Wrap TCP sequence

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST add representable offsets modulo 2^32 and reject offsets above u32

### Requirement: REQ-HOSTFAKE-SPANS — Keep SNI record gaps

The implementation MUST replace only logical Host/SNI bytes while retaining record headers between SNI spans in planner and runtime for TLS 1.2 and 1.3.

#### Scenario: Keep SNI record gaps

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST replace only logical Host/SNI bytes while retaining record headers between SNI spans in planner and runtime for TLS 1.2 and 1.3

### Requirement: REQ-HOSTFAKE-TEMPLATE — Use consistent template validation

The implementation MUST apply one ASCII hostname policy in Kotlin and Rust, rejecting slash, wildcard and Unicode before execution.

#### Scenario: Use consistent template validation

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST apply one ASCII hostname policy in Kotlin and Rust, rejecting slash, wildcard and Unicode before execution

### Requirement: REQ-HOSTFAKE-RANDOM — Retain random mode

The implementation MUST retain randomFakeHost through recommendation, JSON, protobuf, backup and DSL; omit default false from existing JSON and clear it for non-HostFake kinds.

#### Scenario: Retain random mode

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST retain randomFakeHost through recommendation, JSON, protobuf, backup and DSL; omit default false from existing JSON and clear it for non-HostFake kinds

### Requirement: REQ-HOSTFAKE-LATE-ERROR — Prevent late-error replay

The implementation MUST make errors after a raw send attempt terminal so a partially sent Host cannot be replayed as split.

#### Scenario: Prevent late-error replay

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST make errors after a raw send attempt terminal so a partially sent Host cannot be replayed as split

### Requirement: REQ-HOSTFAKE-READINESS — State the raw requirement

The implementation MUST state in settings that HostFake requires a privileged raw sender and otherwise falls back to split.

#### Scenario: State the raw requirement

- **WHEN** the applicable HostFake input or failure path is processed
- **THEN** the implementation MUST state in settings that HostFake requires a privileged raw sender and otherwise falls back to split
