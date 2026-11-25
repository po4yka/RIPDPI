## Purpose

Define the observable completion contract for Complete the AmneziaWG editor. A user can create an AmneziaWG profile by hand without an external key tool and save it without connecting.

## ADDED Requirements

### Requirement: REQ-UIX-1795615858043815-001 — The editor has a name field and the list shows that name

The RIPDPI implementation MUST satisfy this portfolio criterion: The editor has a name field and the list shows that name.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the editor has a name field and the list shows that name

### Requirement: REQ-UIX-1795615858043815-002 — The editor can generate a key pair and shows the public key for copying; the pr…

The RIPDPI implementation MUST satisfy this portfolio criterion: The editor can generate a key pair and shows the public key for copying; the private key stays protected by FLAG_SECURE.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the editor can generate a key pair and shows the public key for copying; the private key stays protected by FLAG_SECURE

### Requirement: REQ-UIX-1795615858043815-003 — Save stores the profile without starting the VPN

The RIPDPI implementation MUST satisfy this portfolio criterion: Save stores the profile without starting the VPN.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that save stores the profile without starting the VPN
