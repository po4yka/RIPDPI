# wire-standalone-amneziawg-profile-transport Specification

## Purpose
Define observable standalone AmneziaWG profile activation and interoperability.

## Requirements

### Requirement: REQ-TRN-1786264762917775-001 — Native standalone runtime

A generic AmneziaWG runtime MUST use configured keys, endpoint, keepalive, optional PSK, and active obfuscation parameters without Cloudflare provisioning. Invalid active obfuscation MUST fail closed.

#### Scenario: Verify criterion 1

- **WHEN** a valid standalone profile starts
- **THEN** the runtime exposes its actual loopback SOCKS listener after an authenticated handshake

### Requirement: REQ-TRN-1786264762917775-002 — Encrypted data plane

Real two-peer Noise_IKpsk2 exchanges MUST pass through the active AmneziaWG codec and transport inner packets unchanged.

#### Scenario: Verify criterion 2

- **WHEN** peers exchange authenticated encrypted payloads
- **THEN** the receiver obtains the original inner bytes

### Requirement: REQ-TRN-1786264762917775-003 — JNI lifecycle boundary

The Android AWG adapter MUST protect outbound sockets before use, contain panics, and own native start/stop through the shared lifecycle.

#### Scenario: Verify criterion 3

- **WHEN** the service starts or stops the adapter
- **THEN** the established JNI contract and protection tests pass

### Requirement: REQ-TRN-1786264762917775-004 — Kotlin native contract

Kotlin configuration MUST match native field names and numeric obfuscation values. Service-owned DNS and routes MUST NOT become undeclared native fields.

#### Scenario: Verify criterion 4

- **WHEN** an AWG request is converted to native configuration
- **THEN** serialization and round-trip contract tests pass

### Requirement: REQ-TRN-1786264762917775-005 — Durable profile selection

Profiles MUST persist under stable opaque IDs. Explicit standalone selection MUST survive stale automatic fallback; safe failed activation MUST restore the prior selection.

#### Scenario: Verify criterion 5

- **WHEN** a saved profile is activated while older startup recovery is pending
- **THEN** the exact profile remains authoritative and the stale attempt cannot clear its pointer

### Requirement: REQ-TRN-1786264762917775-006 — Service application and interface policy

The service MUST acknowledge only the exact requested transport after runtime application. It MUST preserve the TUN during replacement and apply profile DNS, routes, MTU, and address families.

#### Scenario: Verify criterion 6

- **WHEN** a profile starts from idle or replaces another provider
- **THEN** the AWG SOCKS endpoint becomes the tunnel upstream and failed replacement cannot be reported as applied

### Requirement: REQ-TRN-1786264762917775-007 — Editor consent and connect

Save and Connect MUST validate and persist the profile. Connect MUST obtain VPN consent, reject stale or duplicate callbacks, and wait for service application before reporting success.

#### Scenario: Verify criterion 7

- **WHEN** the user accepts a current VPN consent request
- **THEN** exactly one activation occurs; denial or cancellation does not activate

### Requirement: REQ-TRN-1786264762917775-008 — Independent peer interoperability

A device or rootless loopback fixture MUST demonstrate production-runtime interoperability with an independent AmneziaWG implementation, including TCP, UDP source metadata, IPv4/IPv6, and bounded shutdown.

#### Scenario: Verify criterion 8

- **WHEN** the pinned local peer fixture runs
- **THEN** real encrypted exchanges succeed and shutdown joins client tasks and releases their resources
