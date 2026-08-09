## Purpose

Define the observable completion contract for Report OWNED_STACK_ONLY verdict from diagnostic. When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10) works, the diagnostic returns OWNEDSTACKONLY. Surface that as a real verdict, not a failure — "open this host inside the RIPDPI browser" is a legitimate outcome

## ADDED Requirements

### Requirement: REQ-DGN-1786264762917717-001 — Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10…

The RIPDPI implementation MUST satisfy this portfolio criterion: Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10 and no transparent arm succeeded.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10 and no transparent arm succeeded

### Requirement: REQ-DGN-1786264762917717-002 — UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a d…

The RIPDPI implementation MUST satisfy this portfolio criterion: UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser

### Requirement: REQ-DGN-1786264762917717-003 — Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owne…

The RIPDPI implementation MUST satisfy this portfolio criterion: Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owned-stack-only diagnostic evidence is present.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owned-stack-only diagnostic evidence is present

### Requirement: REQ-DGN-1786264762917717-004 — Third-party apps hitting this host in transparent mode get a structured "not su…

The RIPDPI implementation MUST satisfy this portfolio criterion: Third-party apps hitting this host in transparent mode get a structured "not supported in transparent mode" result, not a silent failure.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Third-party apps hitting this host in transparent mode get a structured "not supported in transparent mode" result, not a silent failure
