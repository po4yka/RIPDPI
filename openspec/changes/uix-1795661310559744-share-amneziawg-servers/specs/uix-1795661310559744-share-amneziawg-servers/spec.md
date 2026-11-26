## Purpose

Define the observable completion contract for Share AmneziaWG servers as full .conf files. The user can share a saved AmneziaWG server as a complete `.conf` file, from the same server actions menu that relay servers use.

## ADDED Requirements

### Requirement: REQ-UIX-1795661310559744-001 — An exporter writes a saved AmneziaWG server as a `.conf` file that carries the …

The RIPDPI implementation MUST satisfy this portfolio criterion: An exporter writes a saved AmneziaWG server as a `.conf` file that carries the interface, peer, and every AmneziaWG 3.x obfuscation field the server has.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that an exporter writes a saved AmneziaWG server as a `.conf` file that carries the interface, peer, and every AmneziaWG 3.x obfuscation field the server has

### Requirement: REQ-UIX-1795661310559744-002 — Importing an exported file gives a server equal to the original, for plain Wire…

The RIPDPI implementation MUST satisfy this portfolio criterion: Importing an exported file gives a server equal to the original, for plain WireGuard, AmneziaWG 1.x, and AmneziaWG 3.x servers.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that importing an exported file gives a server equal to the original, for plain WireGuard, AmneziaWG 1.x, and AmneziaWG 3.x servers

### Requirement: REQ-UIX-1795661310559744-003 — AmneziaWG rows in the Servers list offer Share in the server actions menu and o…

The RIPDPI implementation MUST satisfy this portfolio criterion: AmneziaWG rows in the Servers list offer Share in the server actions menu and on long-press, like relay rows.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that amneziaWG rows in the Servers list offer Share in the server actions menu and on long-press, like relay rows

### Requirement: REQ-UIX-1795661310559744-004 — Share for a subscription-owned AmneziaWG server follows the same rule as subscr…

The RIPDPI implementation MUST satisfy this portfolio criterion: Share for a subscription-owned AmneziaWG server follows the same rule as subscription-owned relay servers.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that share for a subscription-owned AmneziaWG server follows the same rule as subscription-owned relay servers
