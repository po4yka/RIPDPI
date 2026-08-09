## Purpose

Define the observable completion contract for Unpin russh after rsa advisory fix. native/rust/Cargo.toml pins russh at exactly =0.62.5 and native/rust/deny.toml suppresses RUSTSEC-2023-0071 (rsa Marvin timing sidechannel) with the justification that:

## ADDED Requirements

### Requirement: REQ-RST-1786264762917304-001 — cargo deny check advisories exits 0 with the RUSTSEC-2023-0071 suppression remo…

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo deny check advisories exits 0 with the RUSTSEC-2023-0071 suppression removed from deny.toml.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo deny check advisories exits 0 with the RUSTSEC-2023-0071 suppression removed from deny.toml

### Requirement: REQ-RST-1786264762917304-002 — cargo nextest run -p ripdpi-ssh --locked green

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run -p ripdpi-ssh --locked green.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run -p ripdpi-ssh --locked green

### Requirement: REQ-RST-1786264762917304-003 — cargo nextest run --workspace --locked green

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run --workspace --locked green.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run --workspace --locked green

### Requirement: REQ-RST-1786264762917304-004 — The =0.62.5 exact pin is removed or updated in Cargo.toml

The RIPDPI implementation MUST satisfy this portfolio criterion: The =0.62.5 exact pin is removed or updated in Cargo.toml.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that The =0.62.5 exact pin is removed or updated in Cargo.toml

### Requirement: REQ-RST-1786264762917304-005 — Commit message references the russh release that resolved the rsa dependency

The RIPDPI implementation MUST satisfy this portfolio criterion: Commit message references the russh release that resolved the rsa dependency.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Commit message references the russh release that resolved the rsa dependency
