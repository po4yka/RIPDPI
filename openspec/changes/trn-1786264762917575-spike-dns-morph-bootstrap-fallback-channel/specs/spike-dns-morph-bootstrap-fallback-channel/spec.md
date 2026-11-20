## Purpose

Define the observable contract of the DNS-Morph v1 delivery channel: an optional, bundle-declared UDP path that delivers a signed subscription body when every HTTPS endpoint of a refresh fails. The wire format is in `docs/server-integration.md` ("DNS-Morph bootstrap channel").

## ADDED Requirements

### Requirement: REQ-TRN-1786264762917575-001 — ripdpi-dns-morph crate compiles for all 4 Android ABIs

The `ripdpi-dns-morph` crate MUST build into `libripdpi.so` for every supported Android ABI, and the JNI facade MUST export `NativeDnsMorphFetcherNativeBindings.jniFetch`.

#### Scenario: Verify criterion 1

- **WHEN** the native workspace builds for armeabi-v7a, arm64-v8a, x86 and x86_64
- **THEN** the crate compiles and the JNI symbol baseline lists the export

### Requirement: REQ-TRN-1786264762917575-002 — Signed payload delivery against a loopback bridge

The client MUST fetch a payload from a loopback bridge that speaks DNS-Morph v1 and MUST return it only when the Ed25519 signature over the session nonce and payload verifies with the declared key. A tampered chunk, a foreign key, a datagram over 512 bytes, or a declared envelope over the payload limit MUST fail the fetch. The socket MUST be protected before its first datagram, and a protection failure MUST send nothing.

#### Scenario: Verify criterion 2

- **WHEN** `cargo test -p ripdpi-dns-morph --locked` runs the loopback bridge tests
- **THEN** the valid payload is delivered and every hostile response is rejected

### Requirement: REQ-TRN-1786264762917575-003 — Ordinary queries stay with the bridge forwarder

The bridge responder MUST leave every query that is not a DNS-Morph v1 request under its zone unanswered by the protocol, so the bridge forwards it to its local resolver.

#### Scenario: Verify criterion 3

- **WHEN** the responder receives an ordinary `A` query for another name
- **THEN** it returns no DNS-Morph answer

### Requirement: REQ-TRN-1786264762917575-004 — Bundle declaration to refresh delivery

A subscription refresh MUST use the bridge declared by the latest accepted bundle only after every HTTPS endpoint failed, MUST install the delivered bundle on success, MUST keep the HTTPS failure and the last members when the bridge fails, and MUST never contact an undeclared bridge.

#### Scenario: Verify criterion 4

- **WHEN** `SubscriptionRefreshDnsMorphTest` imports a bundle that declares `ripdpi.dns_morph` and the next HTTPS refresh fails
- **THEN** the refresh installs the bundle that the bridge delivered

### Requirement: REQ-TRN-1786264762917575-005 — LOW-confidence dedup explicitly resolved in PR description: confirmed NOT a dup…

The change MUST confirm that DNS-Morph is not a duplicate of `ripdpi-dns-resolver` or current bootstrap transport code.

#### Scenario: Verify criterion 5

- **WHEN** the change is reviewed
- **THEN** the dedup boundary is recorded in the task note and the new crate shares no resolver API
