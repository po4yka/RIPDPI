## Purpose

Define the observable completion contract for Add SSH outbound client crate and profile editor. Add a ripdpi-ssh Rust crate that opens direct-tcpip forwarding via SSH (password or private-key auth), plus a SshProfileScreen editor

## ADDED Requirements

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

### Requirement: REQ-OUT-1786264762917254-005 — SshProfileScreen validates host, port, user, and auth selection. Private key is…

The RIPDPI implementation MUST satisfy this portfolio criterion: SshProfileScreen validates host, port, user, and auth selection. Private key is stored via EncryptedFile; never SharedPreferences. (Validation done. Persistence: all profile editors — SSH, AnyTLS, AmneziaWG — are preview-only by design (@HiltViewModel constru….

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that SshProfileScreen validates host, port, user, and auth selection. Private key is stored via EncryptedFile; never SharedPreferences. (Validation done. Persistence: all profile editors — SSH, AnyTLS, AmneziaWG — are preview-only by design (@HiltViewModel constru…

### Requirement: REQ-OUT-1786264762917254-006 — Host key fingerprint is surfaced on first connect with explicit accept / reject…

The RIPDPI implementation MUST satisfy this portfolio criterion: Host key fingerprint is surfaced on first connect with explicit accept / reject action. (deferred: no connect-from-editor path exists; the Rust SshError::HostKeyUntrusted is config-driven with no runtime accept/reject channel, so a connect-time TOFU dialog is….

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Host key fingerprint is surfaced on first connect with explicit accept / reject action. (deferred: no connect-from-editor path exists; the Rust SshError::HostKeyUntrusted is config-driven with no runtime accept/reject channel, so a connect-time TOFU dialog is…

### Requirement: REQ-OUT-1786264762917254-007 — Passphrase and private-key material are redacted in all diagnostic surfaces

The RIPDPI implementation MUST satisfy this portfolio criterion: Passphrase and private-key material are redacted in all diagnostic surfaces.

#### Scenario: Verify criterion 7

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Passphrase and private-key material are redacted in all diagnostic surfaces
