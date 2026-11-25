## Purpose

Define the observable completion contract for Confirm imports and offer to connect to the new server. After an import the user knows what was added and can connect to it in one step; a failed import tells them why.

## ADDED Requirements

### Requirement: REQ-UIX-1795615855953573-001 — A successful link, QR, or file import shows the imported server names and offer…

The RIPDPI implementation MUST satisfy this portfolio criterion: A successful link, QR, or file import shows the imported server names and offers Select and Connect.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a successful link, QR, or file import shows the imported server names and offers Select and Connect

### Requirement: REQ-UIX-1795615855953573-002 — An empty clipboard offers a field to paste or type the link

The RIPDPI implementation MUST satisfy this portfolio criterion: An empty clipboard offers a field to paste or type the link.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that an empty clipboard offers a field to paste or type the link

### Requirement: REQ-UIX-1795615855953573-003 — The subscription confirm step shows the fetched name and server count before Ad…

The RIPDPI implementation MUST satisfy this portfolio criterion: The subscription confirm step shows the fetched name and server count before Add.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the subscription confirm step shows the fetched name and server count before Add

### Requirement: REQ-UIX-1795615855953573-004 — A failed subscription import names the cause (network, HTTP status, format) and…

The RIPDPI implementation MUST satisfy this portfolio criterion: A failed subscription import names the cause (network, HTTP status, format) and uses the word subscription.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a failed subscription import names the cause (network, HTTP status, format) and uses the word subscription
