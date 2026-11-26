## Purpose

Define the observable completion contract for Split selective-availability targets into domestic, foreign datacenter and CDN classes. Freeze diagnostics can show whether connection_freeze results cluster by destination class and by destination AS, because the selective-availability targets carry a class.

## ADDED Requirements

### Requirement: REQ-DGN-1795704974460888-001 — Each selective-availability target carries one class: domestic hosting, foreign...

The RIPDPI implementation MUST satisfy this portfolio criterion: Each selective-availability target carries one class: domestic hosting, foreign datacenter or CDN.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that each selective-availability target carries one class: domestic hosting, foreign datacenter or CDN

### Requirement: REQ-DGN-1795704974460888-002 — Results and redacted exports keep the target class and the destination AS numbe...

The RIPDPI implementation MUST satisfy this portfolio criterion: Results and redacted exports keep the target class and the destination AS number, and no destination address of a user network.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that results and redacted exports keep the target class and the destination AS number, and no destination address of a user network

### Requirement: REQ-DGN-1795704974460888-003 — The connection_freeze classification together with the transfer volume comparis...

The RIPDPI implementation MUST satisfy this portfolio criterion: The connection_freeze classification together with the transfer volume comparison can be grouped by class and by destination AS in one report.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the connection_freeze classification together with the transfer volume comparison can be grouped by class and by destination AS in one report

### Requirement: REQ-DGN-1795704974460888-004 — A response with a wrong status or a wrong byte count is reported as a server re...

The RIPDPI implementation MUST satisfy this portfolio criterion: A response with a wrong status or a wrong byte count is reported as a server response, not as a freeze, because the check compares exact bytes or hash.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a response with a wrong status or a wrong byte count is reported as a server response, not as a freeze, because the check compares exact bytes or hash
