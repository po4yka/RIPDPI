## Purpose

Keep TLS preset validation, metadata and diagnostic first-flight evidence consistent with the behavior that the application observes.

## ADDED Requirements

### Requirement: REQ-STD05-F1 — Separate static validation from wire size

Catalog metadata MUST describe static template validation without asserting an unmeasured ClientHello size.

#### Scenario: Padded preset

- **WHEN** a preset emits a 517-byte TLS record and its static configuration passes validation
- **THEN** metadata reports static validation success without a claim that the record avoids 517 bytes

### Requirement: REQ-STD05-F2 — Keep validation pure

Profile validation MUST NOT consume a connection seed or change the rotation counter.

#### Scenario: Repeated marker validation

- **WHEN** the rotating marker is validated repeatedly without a connection
- **THEN** the counter and the next selected profile are unchanged

### Requirement: REQ-STD05-F3 — Preserve the first-flight plan

A successful TLS probe MUST compute its template plan from its first complete ClientHello record rather than later handshake writes.

#### Scenario: Finished follows ClientHello

- **WHEN** a TLS 1.2 or TLS 1.3 probe completes a real handshake
- **THEN** it retains the plan for the first ClientHello and keeps the complete write transcript for JA3

#### Scenario: Incomplete first record

- **WHEN** the recorded first TLS record is incomplete
- **THEN** plan extraction fails without a panic or a fabricated plan

### Requirement: REQ-STD05-F4 — Accept the default alias consistently

The consumer validation gate MUST accept native_default as the canonical Chrome preset and MUST reject unknown fixed names.

#### Scenario: Alias and invalid name

- **WHEN** native_default and an unknown fixed name are validated
- **THEN** the alias passes and the unknown name fails without profile selection

### Requirement: REQ-STD05-F5 — State the limits of parity evidence

Tests and documentation MUST distinguish implementation snapshots and target labels from measured browser parity and external compatibility.

#### Scenario: Snapshot without browser receipt

- **WHEN** a test compares the configured preset or its own generated fixture
- **THEN** its name and description do not claim verification against a browser version or an actual JA3 or JA4 measurement

### Requirement: REQ-STD05-F6 — Describe the pinned backend limit

The TLS 1.3 cipher-order contract MUST distinguish the BoringSSL C capability from the unavailable safe Rust wrapper and the hardware-dependent default order.

#### Scenario: Reference cipher string

- **WHEN** a preset declares a TLS 1.3 cipher string that is not applied
- **THEN** documentation states that the backend default remains effective and does not claim that C configuration is impossible
