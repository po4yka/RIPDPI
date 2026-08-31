# introduce-ws-transport-port-to-fix-layer-violations Specification

## Purpose
Define the observable completion contract for Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel. The 2026-06-10 architecture audit found two new actionable layering violations (both upward dependencies into the relay-transport layer L7):

## Requirements

### Requirement: REQ-RST-1786264762917569-001 — PR confirms the two edges still exist in cargo metadata

The RIPDPI implementation MUST satisfy this portfolio criterion: PR confirms the two edges still exist in cargo metadata.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR confirms the two edges still exist in cargo metadata

### Requirement: REQ-RST-1786264762917569-002 — New port crate defines the trait; ripdpi-ws-tunnel implements it

The RIPDPI implementation MUST satisfy this portfolio criterion: New port crate defines the trait; ripdpi-ws-tunnel implements it.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that New port crate defines the trait; ripdpi-ws-tunnel implements it

### Requirement: REQ-RST-1786264762917569-003 — Neither ripdpi-ws-bootstrap nor ripdpi-diagnostics-telegram lists ripdpi-ws-tun…

The RIPDPI implementation MUST satisfy this portfolio criterion: Neither ripdpi-ws-bootstrap nor ripdpi-diagnostics-telegram lists ripdpi-ws-tunnel as a direct dep afterward.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Neither ripdpi-ws-bootstrap nor ripdpi-diagnostics-telegram lists ripdpi-ws-tunnel as a direct dep afterward

### Requirement: REQ-RST-1786264762917569-004 — arch-layer-auditor re-run reports R-1 and R-2 resolved, no new cycle

The RIPDPI implementation MUST satisfy this portfolio criterion: arch-layer-auditor re-run reports R-1 and R-2 resolved, no new cycle.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that arch-layer-auditor re-run reports R-1 and R-2 resolved, no new cycle

### Requirement: REQ-RST-1786264762917569-005 — cargo nextest run --locked green for affected crates; cargo deny check clean

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run --locked green for affected crates; cargo deny check clean.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run --locked green for affected crates; cargo deny check clean
