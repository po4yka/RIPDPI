## Purpose

Define the observable completion contract for Offer Select and Connect after importing a RIPDPI bundle file. After the user imports a RIPDPI bundle file, the app names the imported servers and lets the user select one and connect to it in one step.

## ADDED Requirements

### Requirement: REQ-UIX-1795663496674824-001 — After a RIPDPI bundle file import succeeds, the result screen lists the importe…

The RIPDPI implementation MUST satisfy this portfolio criterion: After a RIPDPI bundle file import succeeds, the result screen lists the imported server names.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that after a RIPDPI bundle file import succeeds, the result screen lists the imported server names

### Requirement: REQ-UIX-1795663496674824-002 — The result screen offers Select and Connect for an imported server; Select make…

The RIPDPI implementation MUST satisfy this portfolio criterion: The result screen offers Select and Connect for an imported server; Select makes it the active server through the same selection path as the server list, and Connect also starts the connection when nothing runs.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the result screen offers Select and Connect for an imported server; Select makes it the active server through the same selection path as the server list, and Connect also starts the connection when nothing runs

### Requirement: REQ-UIX-1795663496674824-003 — A running service follows the new selection without a second start

The RIPDPI implementation MUST satisfy this portfolio criterion: A running service follows the new selection without a second start.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a running service follows the new selection without a second start

### Requirement: REQ-UIX-1795663496674824-004 — New text uses the term server, and new strings exist in all ten locales

The RIPDPI implementation MUST satisfy this portfolio criterion: New text uses the term server, and new strings exist in all ten locales.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that new text uses the term server, and new strings exist in all ten locales
