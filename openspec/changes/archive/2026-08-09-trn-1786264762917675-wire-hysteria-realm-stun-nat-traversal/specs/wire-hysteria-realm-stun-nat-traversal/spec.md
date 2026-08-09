## Purpose

Define the observable completion contract for Wire Hysteria Realm STUN-discovered NAT traversal (sing-box v1.14.0-alpha.22). sing-box v1.14.0-alpha.22 (2026-05-11) introduced a Hysteria Realm service that enables direct peer-to-peer Hysteria2 QUIC tunnels between two clients behind separate NATs — without a fixed listening server on a datacenter ASN. Datacenter-path QoS policies, including short-transfer stalls and session-volume caps, can affect conventional Hysteria2 deployments; Realm permits alternate peer placement because the data peer can live on a residential or mobile ASN behind NAT

## ADDED Requirements

### Requirement: REQ-TRN-1786264762917675-001 — Two RIPDPI clients on separate NATs (test-lab relay/ scenario or two real RU-AS…

The RIPDPI implementation MUST satisfy this portfolio criterion: Two RIPDPI clients on separate NATs (test-lab relay/ scenario or two real RU-ASN devices) successfully hole-punch and exchange data via Hysteria2 QUIC.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Two RIPDPI clients on separate NATs (test-lab relay/ scenario or two real RU-ASN devices) successfully hole-punch and exchange data via Hysteria2 QUIC

### Requirement: REQ-TRN-1786264762917675-002 — Empirical NAT-compatibility report: which RU mobile carrier CGNAT configuration…

The RIPDPI implementation MUST satisfy this portfolio criterion: Empirical NAT-compatibility report: which RU mobile carrier CGNAT configurations succeed / fail with STUN hole-punch.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Empirical NAT-compatibility report: which RU mobile carrier CGNAT configurations succeed / fail with STUN hole-punch

### Requirement: REQ-TRN-1786264762917675-003 — Diagnostic verdict surfaces HYSTERIAREALMOK / HYSTERIAREALMFAILSTUN / HYSTERIAR…

The RIPDPI implementation MUST satisfy this portfolio criterion: Diagnostic verdict surfaces HYSTERIAREALMOK / HYSTERIAREALMFAILSTUN / HYSTERIAREALMFAILPUNCH distinguished by phase.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Diagnostic verdict surfaces HYSTERIAREALMOK / HYSTERIAREALMFAILSTUN / HYSTERIAREALMFAILPUNCH distinguished by phase

### Requirement: REQ-TRN-1786264762917675-004 — LOW-confidence dedup resolved in PR description: confirmed Realm functionality…

The RIPDPI implementation MUST satisfy this portfolio criterion: LOW-confidence dedup resolved in PR description: confirmed Realm functionality not previously available in ripdpi-hysteria2.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that LOW-confidence dedup resolved in PR description: confirmed Realm functionality not previously available in ripdpi-hysteria2
