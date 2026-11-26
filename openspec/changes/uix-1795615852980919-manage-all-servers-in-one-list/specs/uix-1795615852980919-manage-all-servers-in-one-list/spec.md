## Purpose

Define the observable completion contract for Manage all servers in one list. The user sees, adds, selects, and manages every saved server in one list, with one name for servers and another for DPI presets.

## ADDED Requirements

### Requirement: REQ-UIX-1795615852980919-001 — The Add menu offers every relay kind that has an editor in `RelayKindDescriptor…

The RIPDPI implementation MUST satisfy this portfolio criterion: The Add menu offers every relay kind that has an editor in `RelayKindDescriptors`, plus AmneziaWG.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the Add menu offers every relay kind that has an editor in `RelayKindDescriptors`, plus AmneziaWG

### Requirement: REQ-UIX-1795615852980919-002 — Saved AmneziaWG profiles appear in the same Servers list as relay profiles

The RIPDPI implementation MUST satisfy this portfolio criterion: Saved AmneziaWG profiles appear in the same Servers list as relay profiles.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that saved AmneziaWG profiles appear in the same Servers list as relay profiles

### Requirement: REQ-UIX-1795615852980919-003 — Each server has rename, duplicate, and delete actions, and relay servers have share

The RIPDPI implementation MUST satisfy this portfolio criterion: Each relay server has rename, duplicate, share, and delete actions, each AmneziaWG server has rename, duplicate, and delete actions, and long-press opens these actions; AmneziaWG share is tracked in UIX-1795661310559744.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that each relay server has rename, duplicate, share, and delete actions, each AmneziaWG server has rename, duplicate, and delete actions, and long-press opens these actions; AmneziaWG share is tracked in UIX-1795661310559744

### Requirement: REQ-UIX-1795615852980919-004 — The server list surfaces use one term for servers and another for DPI presets

The RIPDPI implementation MUST satisfy this portfolio criterion: The Connection sheet, Add server menu, server editors, and share action use one term for servers and a different term for DPI presets in all ten locales; the remaining strings are tracked in UIX-1795661374629256.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the Connection sheet, Add server menu, server editors, and share action use one term for servers and a different term for DPI presets in all ten locales; the remaining strings are tracked in UIX-1795661374629256

### Requirement: REQ-UIX-1795615852980919-005 — Selecting a server does not change the DPI preset without telling the user

The RIPDPI implementation MUST satisfy this portfolio criterion: Selecting a server does not change the DPI preset without telling the user.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that selecting a server does not change the DPI preset without telling the user

### Requirement: REQ-UIX-1795615852980919-006 — The Connection screen keeps the selected Relay view when the user returns to it

The RIPDPI implementation MUST satisfy this portfolio criterion: The Connection screen keeps the selected Relay view when the user returns to it.

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the Connection screen keeps the selected Relay view when the user returns to it
