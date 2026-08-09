## Purpose

Define the observable completion contract for Reduce pub surface of monitor-engine/config and add golden contracts for high-fan-in crates. The 2026-06-10 Rust API audit flagged visibility bloat and blast-radius risk:

## ADDED Requirements

### Requirement: REQ-RST-1786264762917430-001 — ripdpi-monitor-engine pub-item count meaningfully reduced; no external consumer…

The RIPDPI implementation MUST satisfy this portfolio criterion: ripdpi-monitor-engine pub-item count meaningfully reduced; no external consumer breaks.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that ripdpi-monitor-engine pub-item count meaningfully reduced; no external consumer breaks

### Requirement: REQ-RST-1786264762917430-002 — ripdpi-config lib.rs documents its true role

The RIPDPI implementation MUST satisfy this portfolio criterion: ripdpi-config lib.rs documents its true role.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that ripdpi-config lib.rs documents its true role

### Requirement: REQ-RST-1786264762917430-003 — Golden-contract tests exist for ripdpi-failure-classifier and ripdpi-config pub…

The RIPDPI implementation MUST satisfy this portfolio criterion: Golden-contract tests exist for ripdpi-failure-classifier and ripdpi-config public surfaces.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Golden-contract tests exist for ripdpi-failure-classifier and ripdpi-config public surfaces

### Requirement: REQ-RST-1786264762917430-004 — cargo nextest run --locked green workspace-wide; clippy clean

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run --locked green workspace-wide; clippy clean.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run --locked green workspace-wide; clippy clean
