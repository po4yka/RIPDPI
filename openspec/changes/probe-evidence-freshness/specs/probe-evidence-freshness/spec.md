## ADDED Requirements

### Requirement: REQ-DGN-1795329243655314-001 — Count selector probes on their original network

A participating probe SHALL capture the allowed report label and the network generation before measuring. The ledger SHALL count it only when the same generation and consent still apply at commit.

#### Scenario: The network changes during a probe

- **WHEN** Wi-Fi changes to cellular, with or without a return to the original Wi-Fi
- **THEN** the ledger discards that measurement and the selector keeps its own probe result

### Requirement: REQ-DGN-1795329243655314-002 — Confirm direct-path failure with fresh evidence

The learner SHALL require two distinct fresh failures for the same authority, IP set, runtime run, and network generation before it learns or extends `NO_DIRECT_SOLUTION`.

#### Scenario: A failure is duplicated or expired

- **WHEN** the same observation arrives again or the first observation expires
- **THEN** the learner does not confirm or extend the negative policy from that pair
