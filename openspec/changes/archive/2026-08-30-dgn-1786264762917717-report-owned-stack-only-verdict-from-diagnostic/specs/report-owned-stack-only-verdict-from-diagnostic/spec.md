## Purpose

Define the observable completion contract for Report OWNED_STACK_ONLY verdict from diagnostic. When transparent arms (A3–A8) all fail but an owned-stack arm (A9/A10) works, the diagnostic returns OWNEDSTACKONLY. Surface that as a real verdict, not a failure — "open this host inside the RIPDPI browser" is a legitimate outcome

## ADDED Requirements

### Requirement: REQ-DGN-1786264762917717-001 — Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10…

The RIPDPI implementation MUST satisfy this portfolio criterion: Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10 and no transparent arm succeeded.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Diagnostic orchestrator emits OWNEDSTACKONLY when the winning arm is A9 or A10 and no transparent arm succeeded

### Requirement: REQ-DGN-1786264762917717-002 — UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a d…

The RIPDPI implementation MUST satisfy this portfolio criterion: UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that UI/diagnostics surface: "Transparent mode: no / Owned-stack mode: yes" with a direct action to open the URL in the in-app browser

### Requirement: REQ-DGN-1786264762917717-003 — Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owne…

The RIPDPI implementation MUST satisfy this portfolio criterion: Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owned-stack-only diagnostic evidence is present.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Persisted policy sets outcome = OWNEDSTACKONLY on the TransportPolicy when owned-stack-only diagnostic evidence is present

### Requirement: REQ-DGN-1786264762917717-004 — Third-party apps hitting this host in transparent mode get a structured "not su…

The RIPDPI implementation MUST satisfy this portfolio criterion: Third-party apps hitting this host in transparent mode get a structured "not supported in transparent mode" result, not a silent failure.

#### Scenario: Verify criterion 4

- **WHEN** a hostname-attributed transparent TCP request matches a direct-path capability whose outcome is `OWNED_STACK_ONLY`
- **THEN** runtime admission MUST reject it with the stable internal reason `OWNED_STACK_REQUIRED` before WebSocket fallback, delayed-connect success, relay selection, or upstream socket creation
- **AND** a SOCKS5 client MUST receive `REP=0x02` and an HTTP CONNECT client MUST receive `403 Forbidden` with `X-RIPDPI-Reason: OWNED_STACK_REQUIRED`
- **AND** the existing runtime telemetry snapshot MUST expose an `OWNED_STACK_REQUIRED` direct-path event without disclosing the destination in the response body

#### Scenario: Preserve hostless transparent traffic

- **WHEN** transparent ingress has only an original destination IP and no hostname attribution
- **THEN** the runtime MUST NOT apply an authority-specific `OWNED_STACK_ONLY` policy based on that IP alone

#### Scenario: Preserve IP-literal transparent traffic

- **WHEN** a SOCKS domain field or HTTP host field carries an IPv4 or IPv6 literal instead of a DNS hostname
- **THEN** the runtime MUST NOT treat the literal as hostname attribution or apply an IP-scoped `OWNED_STACK_ONLY` policy

#### Scenario: Preserve capability scope

- **WHEN** the hostname matches but the capability's non-empty IP-set digest does not match the resolved targets
- **THEN** the runtime MUST NOT apply the `OWNED_STACK_ONLY` rejection
- **AND** an exact matching digest MUST take priority over an empty wildcard digest regardless of capability record order
