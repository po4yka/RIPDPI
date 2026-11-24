# add-ssh-outbound-client-crate-and-profile-editor Specification

## Purpose

Define the observable completion contract for Add SSH outbound client crate and profile editor. Add a ripdpi-ssh Rust crate that opens direct-tcpip forwarding via SSH (password or private-key auth), plus a SshProfileScreen editor

## Requirements

### Requirement: REQ-OUT-1786264762917254-001 — ripdpi-ssh crate compiles with a maintained SSH crate dependency (evaluate russ…

The RIPDPI implementation MUST satisfy this portfolio criterion: ripdpi-ssh crate compiles with a maintained SSH crate dependency (evaluate russh, thrussh successors).

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that ripdpi-ssh crate compiles with a maintained SSH crate dependency (evaluate russh, thrussh successors)

### Requirement: REQ-OUT-1786264762917254-002 — Password and OpenSSH private-key auth both supported

The RIPDPI implementation MUST satisfy this portfolio criterion: Password and OpenSSH private-key auth both supported.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Password and OpenSSH private-key auth both supported

### Requirement: REQ-OUT-1786264762917254-003 — Host-key verification is on by default; "trust on first use" is a per-profile o…

The RIPDPI implementation MUST satisfy this portfolio criterion: Host-key verification is on by default; "trust on first use" is a per-profile opt-in.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Host-key verification is on by default; "trust on first use" is a per-profile opt-in

### Requirement: REQ-OUT-1786264762917254-004 — direct-tcpip forwarding to arbitrary target host:port works for TCP; UDP is out…

The RIPDPI implementation MUST satisfy this portfolio criterion: direct-tcpip forwarding to arbitrary target host:port works for TCP; UDP is out of scope for v1.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that direct-tcpip forwarding to arbitrary target host:port works for TCP; UDP is out of scope for v1

### Requirement: REQ-OUT-1786264762917254-005 — Validate and securely persist SSH profiles

The SSH editor MUST validate host, port, user, and authentication. It MUST store private keys and passphrases under the atomic profile persistence contract. Secrets are encrypted with a key from Android Keystore before their records are written in the Room transaction.

#### Scenario: Save a valid private-key profile

- **WHEN** the user saves a valid profile with private-key authentication
- **THEN** the profile and its encrypted secrets are stored in one transaction

### Requirement: REQ-OUT-1786264762917254-006 — Confirm the observed host key before saving

The SSH editor MUST show the observed host key fingerprint before saving a new profile without a pin. The user can accept or reject the fingerprint.

#### Scenario: Decide whether to trust the observed key

- **WHEN** the user accepts the fingerprint, RIPDPI saves the profile with the observed key pinned
- **THEN** the saved profile requires that pin on later connections

#### Scenario: Reject the observed key

- **WHEN** the user rejects the fingerprint
- **THEN** RIPDPI leaves the profile unsaved

### Requirement: REQ-OUT-1786264762917254-007 — Passphrase and private-key material are redacted in all diagnostic surfaces

The RIPDPI implementation MUST satisfy this portfolio criterion: Passphrase and private-key material are redacted in all diagnostic surfaces.

#### Scenario: Verify criterion 7

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Passphrase and private-key material are redacted in all diagnostic surfaces
