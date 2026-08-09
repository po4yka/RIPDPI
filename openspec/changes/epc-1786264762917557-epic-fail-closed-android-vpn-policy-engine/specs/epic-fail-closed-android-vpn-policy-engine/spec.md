## Purpose

Define the observable completion contract for Epic - Fail-closed Android VPN policy engine. Make RIPDPI a fail-closed policy-first Android tunneled outbound profile, not just a GUI for imported proxy links. The app should eliminate the common failure classes in existing clients: incomplete policy bundles, DNS and IPv6 leaks, weak kill-switch UX, shared subscriptions, manual-only failover, unsafe logs, and untested VPN lifecycle behavior

## ADDED Requirements

### Requirement: REQ-EPC-1786264762917557-001 — Internal VPN profile is a typed policy bundle, not only imported URI strings

The RIPDPI implementation MUST satisfy this portfolio criterion: Internal VPN profile is a typed policy bundle, not only imported URI strings.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Internal VPN profile is a typed policy bundle, not only imported URI strings

### Requirement: REQ-EPC-1786264762917557-002 — Secure default captures full-device traffic with DNS interception and explicit…

The RIPDPI implementation MUST satisfy this portfolio criterion: Secure default captures full-device traffic with DNS interception and explicit IPv4-only policy.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Secure default captures full-device traffic with DNS interception and explicit IPv4-only policy

### Requirement: REQ-EPC-1786264762917557-003 — Lockdown onboarding clearly distinguishes Android system kill switch from soft…

The RIPDPI implementation MUST satisfy this portfolio criterion: Lockdown onboarding clearly distinguishes Android system kill switch from soft reconnect.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Lockdown onboarding clearly distinguishes Android system kill switch from soft reconnect

### Requirement: REQ-EPC-1786264762917557-004 — Core crash, network switch, and VPN revoke paths fail closed in tests

The RIPDPI implementation MUST satisfy this portfolio criterion: Core crash, network switch, and VPN revoke paths fail closed in tests.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Core crash, network switch, and VPN revoke paths fail closed in tests

### Requirement: REQ-EPC-1786264762917557-005 — Logs, diagnostics, crash exports, QR/import, and subscription refreshes redact…

The RIPDPI implementation MUST satisfy this portfolio criterion: Logs, diagnostics, crash exports, QR/import, and subscription refreshes redact live credentials.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Logs, diagnostics, crash exports, QR/import, and subscription refreshes redact live credentials
