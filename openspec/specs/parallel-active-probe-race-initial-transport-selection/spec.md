# parallel-active-probe-race-initial-transport-selection Specification

## Purpose
Define the observable completion contract for Add a parallel active-probe race for initial transport selection. Race the simple flavor's seeded VLESS+Reality and Hysteria2+Salamander relay paths with an application-level probe before the VPN TUN is exposed, select the first confirmed-good transport, and retain the existing post-connection failover and UCB1 behavior

## Requirements

### Requirement: REQ-TRN-1786264762917886-001 — A stalled Reality application exchange does not delay selection of a healthy Hy…

The RIPDPI implementation MUST satisfy this portfolio criterion: A stalled Reality application exchange does not delay selection of a healthy Hysteria2 path until the legacy timeout.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that A stalled Reality application exchange does not delay selection of a healthy Hysteria2 path until the legacy timeout

### Requirement: REQ-TRN-1786264762917886-002 — A blocked UDP path selects healthy Reality

The RIPDPI implementation MUST satisfy this portfolio criterion: A blocked UDP path selects healthy Reality.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that A blocked UDP path selects healthy Reality

### Requirement: REQ-TRN-1786264762917886-003 — The TUN is not established before a probe-confirmed winner or eligible cached f…

The RIPDPI implementation MUST satisfy this portfolio criterion: The TUN is not established before a probe-confirmed winner or eligible cached fallback exists.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that The TUN is not established before a probe-confirmed winner or eligible cached fallback exists

### Requirement: REQ-TRN-1786264762917886-004 — The first valid HTTP 2xx response wins and the losing runtime is stopped withou…

The RIPDPI implementation MUST satisfy this portfolio criterion: The first valid HTTP 2xx response wins and the losing runtime is stopped without surfacing an unexpected-exit event.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that The first valid HTTP 2xx response wins and the losing runtime is stopped without surfacing an unexpected-exit event

### Requirement: REQ-TRN-1786264762917886-005 — Cached fallback is scoped by hashed network identity and candidate signature, e…

The RIPDPI implementation MUST satisfy this portfolio criterion: Cached fallback is scoped by hashed network identity and candidate signature, expires after 24 hours, and is not refreshed by fallback use.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Cached fallback is scoped by hashed network identity and candidate signature, expires after 24 hours, and is not refreshed by fallback use

### Requirement: REQ-TRN-1786264762917886-006 — Handover re-races; self-induced post-connection failover restart does not

The RIPDPI implementation MUST satisfy this portfolio criterion: Handover re-races; self-induced post-connection failover restart does not.

#### Scenario: Verify criterion 6

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Handover re-races; self-induced post-connection failover restart does not

### Requirement: REQ-TRN-1786264762917886-007 — Integrated-tree gate evidence is complete

The change MUST pass its focused Rust, Kotlin, simple-flavor, architecture, static-analysis, and controlled relay-lab gates on the integrated tree.

#### Scenario: Verify the integrated gate set

- **WHEN** the change is rebased onto the target integration SHA and the named gates run
- **THEN** every required gate MUST have an observed passing result or an explicitly blocked evidence state that keeps the task open
