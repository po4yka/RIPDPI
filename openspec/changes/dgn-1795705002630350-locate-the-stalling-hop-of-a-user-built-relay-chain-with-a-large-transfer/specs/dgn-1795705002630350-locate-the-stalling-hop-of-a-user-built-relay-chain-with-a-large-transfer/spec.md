## Purpose

Define the observable completion contract for Locate the stalling hop of a user-built relay chain with a large transfer. For a relay chain that the user built on the client, diagnostics shows the first hop where a large transfer stops.

## ADDED Requirements

### Requirement: REQ-DGN-1795705002630350-001 — For a user-built chain, diagnostics transfers a payload larger than the TCP-fre...

The RIPDPI implementation MUST satisfy this portfolio criterion: For a user-built chain, diagnostics transfers a payload larger than the TCP-freeze threshold through each hop prefix in order and reports the first hop where the volume stops, with the byte count at the stop.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that for a user-built chain, diagnostics transfers a payload larger than the TCP-freeze threshold through each hop prefix in order and reports the first hop where the volume stops, with the byte count at the stop

### Requirement: REQ-DGN-1795705002630350-002 — A server-side cascade is probed as one endpoint, and the report does not claim...

The RIPDPI implementation MUST satisfy this portfolio criterion: A server-side cascade is probed as one endpoint, and the report does not claim per-hop results for it.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a server-side cascade is probed as one endpoint, and the report does not claim per-hop results for it

### Requirement: REQ-DGN-1795705002630350-003 — The per-hop result reuses the transfer measurement and payload controls of the...

The RIPDPI implementation MUST satisfy this portfolio criterion: The per-hop result reuses the transfer measurement and payload controls of the connection test task.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the per-hop result reuses the transfer measurement and payload controls of the connection test task
