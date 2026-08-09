## Purpose

Define the observable completion contract for Guard RelayBackend manual match arms against silently-omitted QUIC variants. The 2026-06-10 Rust API audit noted RelayBackend reached 14 variants (was 12; Mieru and Ssh added). The dispatchpooledbackend! macro was updated correctly. Re-verified 2026-06-11 against native/rust/crates/ripdpi-relay-core/src/backend.rs: of the three manual match self blocks, quicmigrationsnapshot() (backend.rs:85-102) and openudpsession() (backend.rs:122-141) already enumerate all 14 variants with explicit |-joined arms and no catch-all , so adding a variant fails to compile (non-exhaustive…

## ADDED Requirements

### Requirement: REQ-RLY-1786264762917178-001 — PR confirms current 14-variant shape and the three manual-match sites

The RIPDPI implementation MUST satisfy this portfolio criterion: PR confirms current 14-variant shape and the three manual-match sites.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR confirms current 14-variant shape and the three manual-match sites

### Requirement: REQ-RLY-1786264762917178-002 — Adding a new RelayBackend variant now fails to compile until the QUIC/chain/UDP…

The RIPDPI implementation MUST satisfy this portfolio criterion: Adding a new RelayBackend variant now fails to compile until the QUIC/chain/UDP snapshot matches are updated (no silent (None, None)).

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Adding a new RelayBackend variant now fails to compile until the QUIC/chain/UDP snapshot matches are updated (no silent (None, None))

### Requirement: REQ-RLY-1786264762917178-003 — cargo nextest run -p ripdpi-relay-core --locked green; clippy clean

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run -p ripdpi-relay-core --locked green; clippy clean.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run -p ripdpi-relay-core --locked green; clippy clean
