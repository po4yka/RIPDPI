## Purpose

Define the observable completion contract for Per-exit-IP TLS cap with true mux-preference in relay-core backend. The per-exit-IP concurrent-TLS cap (ExitIpSessionLimiter, ripdpi-proxy-runtime/src/exitipcap.rs) was wired into ripdpi-proxy-runtime's outbound connect path as an admission gate with route-preference on cap (skip an at-cap exit-IP candidate for an alternate; advisory fall-through when all are capped). That closed the originally-filed task

## ADDED Requirements

### Requirement: REQ-TRN-1786264762917184-001 — Per-exit-IP concurrent-session cap enforced on the relay-core foreign-exit path…

The RIPDPI implementation MUST satisfy this portfolio criterion: Per-exit-IP concurrent-session cap enforced on the relay-core foreign-exit path (the path that actually opens VLESS+Reality+Vision TLS sessions).

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Per-exit-IP concurrent-session cap enforced on the relay-core foreign-exit path (the path that actually opens VLESS+Reality+Vision TLS sessions)

### Requirement: REQ-TRN-1786264762917184-002 — At cap, the next stream reuses an existing muxed session via RelayMux::openstre…

The RIPDPI implementation MUST satisfy this portfolio criterion: At cap, the next stream reuses an existing muxed session via RelayMux::openstream (true mux-preference), verified by a test.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that At cap, the next stream reuses an existing muxed session via RelayMux::openstream (true mux-preference), verified by a test

### Requirement: REQ-TRN-1786264762917184-003 — No double-counting between the proxy-runtime direct-path gate and the relay-cor…

The RIPDPI implementation MUST satisfy this portfolio criterion: No double-counting between the proxy-runtime direct-path gate and the relay-core cap.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that No double-counting between the proxy-runtime direct-path gate and the relay-core cap

### Requirement: REQ-TRN-1786264762917184-004 — cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clip…

The RIPDPI implementation MUST satisfy this portfolio criterion: cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clippy clean; pr-reviewer pass (hot path).

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that cargo nextest run -p ripdpi-relay-core -p ripdpi-relay-mux --locked green; clippy clean; pr-reviewer pass (hot path)
