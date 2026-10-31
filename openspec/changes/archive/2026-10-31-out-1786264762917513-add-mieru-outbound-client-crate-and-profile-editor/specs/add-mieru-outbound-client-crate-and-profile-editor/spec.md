## Purpose

Define the observable completion contract for Add Mieru outbound client crate and profile editor. Add a ripdpi-mieru Rust crate implementing the Mieru outbound client and a MieruProfileScreen editor. Mieru (enfein/mieru) is actively developed and used by Mieru-compatible deployments; omitting it blocks interoperability with that user cohort

## ADDED Requirements

### Requirement: REQ-OUT-1786264762917513-001 — Multiplexing implemented for low/middle/high (mux.rs): many sessionID-tagged su…

The RIPDPI implementation MUST satisfy this portfolio criterion: Multiplexing implemented for low/middle/high (mux.rs): many sessionID-tagged sub-sessions share one carrier. A single serialized Encryptor keeps the per-direction nonce monotonic (nonce-reuse-safe under concurrent streams); a single reader task demuxes inboun….

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Multiplexing implemented for low/middle/high (mux.rs): many sessionID-tagged sub-sessions share one carrier. A single serialized Encryptor keeps the per-direction nonce monotonic (nonce-reuse-safe under concurrent streams); a single reader task demuxes inboun…

### Requirement: REQ-OUT-1786264762917513-002 — MieruProfileScreen validates server + port, username, password, protocol mode (…

The RIPDPI implementation MUST satisfy this portfolio criterion: MieruProfileScreen validates server + port, username, password, protocol mode (TCP/UDP), mTU.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that MieruProfileScreen validates server + port, username, password, protocol mode (TCP/UDP), mTU

### Requirement: REQ-OUT-1786264762917513-003 — The replay key comes from a shared network-time source, never a direct device-c…

The RIPDPI implementation MUST satisfy this portfolio criterion: The replay key comes from a shared network-time source, never a direct device-clock read. Implemented the workspace's first network-time provider (ripdpi-network-time: monotonic-from-anchor with device-clock fallback), wired the relay facade to it (replacing….

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that The replay key comes from a shared network-time source, never a direct device-clock read. Implemented the workspace's first network-time provider (ripdpi-network-time: monotonic-from-anchor with device-clock fallback), wired the relay facade to it (replacing…

### Requirement: REQ-OUT-1786264762917513-004 — Credentials redacted in all diagnostic surfaces

The RIPDPI implementation MUST satisfy this portfolio criterion: Credentials redacted in all diagnostic surfaces.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Credentials redacted in all diagnostic surfaces

### Requirement: REQ-OUT-1786264762917513-005 — Subscription import path recognizes mieru:// URIs

The RIPDPI implementation MUST satisfy this portfolio criterion: Subscription import path recognizes mieru:// URIs.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Subscription import path recognizes mieru:// URIs

### Requirement: REQ-OUT-1786264762917513-006 — Mieru TCP interoperability has an upstream oracle

The implemented Mieru TCP carrier MUST pass upstream reference vectors or a live-server interoperability test; an in-crate self-consistency test alone does not satisfy this requirement.

#### Scenario: Verify upstream interoperability

- **WHEN** the Mieru TCP carrier exchanges a session with the selected upstream oracle
- **THEN** the observed wire behavior and payload exchange MUST pass

### Requirement: REQ-OUT-1786264762917513-007 — Deferred Mieru UDP scope is resolved

The change MUST implement and verify the Mieru UDP carrier or record an approved scope decision that keeps the portfolio criterion open in a follow-up task.

#### Scenario: Verify UDP scope

- **WHEN** the Mieru transport modes are reviewed for completion
- **THEN** UDP support MUST be verified or remain explicitly open rather than being reported complete
