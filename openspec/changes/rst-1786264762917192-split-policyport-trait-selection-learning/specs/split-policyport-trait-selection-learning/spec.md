## Purpose

Define the observable completion contract for Split the 12-method PolicyPort trait into selection and learning sub-traits. The 2026-06-10 Rust API audit flagged an Interface-Segregation violation. ripdpi-runtime-decision-ports/src/policy.rs:138 — PolicyPort now has 12 methods (threshold 8): selectinitial, notesuccess, advanceroute, noteblocksignal, supportstrigger, selectnext, storeroute, clearconnectioncache, buildretrypenalties, autolearnstate, drainautolearnevents, flushhoststore. Callers that only select routes are forced to depend on (and mock, in tests) the full learning surface

## ADDED Requirements

### Requirement: REQ-RST-1786264762917192-001 — PR confirms current 12-method shape at policy.rs:138

The RIPDPI implementation MUST satisfy this portfolio criterion: PR confirms current 12-method shape at policy.rs:138.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR confirms current 12-method shape at policy.rs:138

### Requirement: REQ-RST-1786264762917192-002 — Two sub-traits exist; selection-only and learning-only callers depend on the na…

The RIPDPI implementation MUST satisfy this portfolio criterion: Two sub-traits exist; selection-only and learning-only callers depend on the narrower one.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Two sub-traits exist; selection-only and learning-only callers depend on the narrower one

### Requirement: REQ-RST-1786264762917192-003 — No behavior change; existing impls satisfy both

The RIPDPI implementation MUST satisfy this portfolio criterion: No behavior change; existing impls satisfy both.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that No behavior change; existing impls satisfy both

### Requirement: REQ-RST-1786264762917192-004 — Test mocks simplify (selection tests no longer stub learning methods)

The RIPDPI implementation MUST satisfy this portfolio criterion: Test mocks simplify (selection tests no longer stub learning methods).

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Test mocks simplify (selection tests no longer stub learning methods)

### Requirement: REQ-RST-1786264762917192-005 — cargo nextest run --locked green for the decision-ports consumers; clippy clean

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run --locked green for the decision-ports consumers; clippy clean.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run --locked green for the decision-ports consumers; clippy clean
