## Purpose

Preserve the supported AWG profile meaning across import, local storage, and native activation without changing its wire format.

## ADDED Requirements

### Requirement: REQ-AWG-INPUT-IPV6 — Preserve endpoint host identity

URI import and export MUST preserve an IPv6 host without syntax brackets in the profile model. URI authority and configuration Endpoint output MUST contain one bracket pair for IPv6. Hostnames and IPv4 MUST retain their meaning.

#### Scenario: Import an IPv6 share link

- **WHEN** a valid IPv6 share link passes through configuration import and activation mapping
- **THEN** the native request contains the original bare IPv6 host and the original port.

### Requirement: REQ-AWG-INPUT-HPKEY — Use one canonical header key contract

All profile input paths MUST accept only canonical padded Base64 that represents exactly 32 bytes for a set header protection key. The unset and canonical zero-key off values MUST retain their meaning. Errors MUST NOT expose key material.

#### Scenario: Reject a key that native code cannot decode

- **WHEN** input has missing padding, noncanonical pad bits, or an incorrect decoded length
- **THEN** validation rejects it before activation with no key material in the error.

### Requirement: REQ-AWG-INPUT-QUERY — Reject ambiguous query input

URI decode MUST reject query tokens without a separator, empty query tokens, empty names, and duplicate names instead of dropping them or replacing values. Supported values containing encoded separators MUST still round trip.

#### Scenario: Reject a malformed or duplicate wire field

- **WHEN** an otherwise valid share link has a bare field or duplicate field
- **THEN** decode returns no profile instead of selecting a default or another value.

### Requirement: REQ-AWG-STORED-WIRE — Preserve unsupported stored requests

A stored request with an unknown field anywhere inside its wire obfuscation object MUST remain incompatible and MUST NOT activate. Validation MUST leave its stored row and credentials unchanged. Unrelated top-level metadata MUST remain tolerated.

#### Scenario: Load an unknown nested wire setting

- **WHEN** a stored profile contains an unknown v3 field
- **THEN** load and profile observation exclude it while its exact row and secrets remain intact.

### Requirement: REQ-AWG-INPUT-CARRIER — Reject unsupported carrier tokens

Activation MUST allow only udp and ws carrier tokens. An omitted carrier MUST retain the UDP default. An unknown or empty token MUST fail before native startup and MUST NOT select UDP.

#### Scenario: Load an old request or reject an unknown carrier

- **WHEN** the carrier field is absent from an old request
- **THEN** the request uses UDP.
- **WHEN** a request contains an unsupported carrier token
- **THEN** activation and runtime resolution reject it before network setup.
